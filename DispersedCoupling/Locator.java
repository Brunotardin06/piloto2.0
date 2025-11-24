/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.tools.ant.launch;

import java.net.MalformedURLException;          // MalformedURLException (JDK): signals invalid URL syntax
import java.net.URL;                            // URL (JDK): represents "file:", "jar:file:" and other resource locations
import java.io.File;                            // File (JDK): platform-dependent representation of files/directories
import java.io.FilenameFilter;                  // FilenameFilter (JDK): callback used by File.listFiles(...) to filter names
import java.io.ByteArrayOutputStream;           // ByteArrayOutputStream (JDK): in-memory byte buffer built incrementally
import java.io.UnsupportedEncodingException;    // UnsupportedEncodingException (JDK): thrown if charset (e.g. UTF-8) is unavailable
import java.text.CharacterIterator;             // CharacterIterator (JDK): generic cursor over characters of a text
import java.text.StringCharacterIterator;       // StringCharacterIterator (JDK): CharacterIterator implementation backed by a String
import java.util.Locale;                        // Locale (JDK): used to enforce locale-independent casing (Locale.US)

/**
 * The Locator is a utility class which is used to find certain items
 * in the environment.
 *
 * @since Ant 1.6
 */
public final class Locator {

    /**
     * encoding used to represent URIs
     */
    public static final String URI_ENCODING = "UTF-8"; // URI_ENCODING: charset used when encoding/decoding URIs

    // Lookup tables used by encodeURI(...) to decide which ASCII chars to escape and how
    private static boolean[] gNeedEscaping = new boolean[128]; // gNeedEscaping: flags ASCII chars that must be percent-encoded
    private static char[] gAfterEscaping1 = new char[128];     // gAfterEscaping1: first hex digit for each escaped ASCII char
    private static char[] gAfterEscaping2 = new char[128];     // gAfterEscaping2: second hex digit for each escaped ASCII char
    private static char[] gHexChs = {                          // gHexChs: hex digit lookup table (0–F) used to build %xx sequences
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    static {
        // Mark control characters (0x00–0x1F) and DEL (0x7F) as needing escaping
        for (int i = 0; i <= 0x1f; i++) {
            gNeedEscaping[i] = true;
            gAfterEscaping1[i] = gHexChs[i >> 4];
            gAfterEscaping2[i] = gHexChs[i & 0xf];
        }
        gNeedEscaping[0x7f] = true;
        gAfterEscaping1[0x7f] = '7';
        gAfterEscaping2[0x7f] = 'F';

        // Additional reserved/unsafe characters in URIs
        char[] escChs = {' ', '<', '>', '#', '%', '"', '{', '}',
                         '|', '\\', '^', '~', '[', ']', '`'};
        int len = escChs.length;
        char ch;
        for (int i = 0; i < len; i++) {
            ch = escChs[i];
            gNeedEscaping[ch] = true;
            gAfterEscaping1[ch] = gHexChs[ch >> 4];
            gAfterEscaping2[ch] = gHexChs[ch & 0xf];
        }
    }

    /**
     * Not instantiable
     */
    private Locator() {
        // Private constructor: utility class, only static methods are used
    }

    /**
     * Find the directory or jar file the class has been loaded from.
     */
    public static File getClassSource(Class c) {
        String classResource = c.getName().replace('.', '/') + ".class";
        return getResourceSource(c.getClassLoader(), classResource); // delegates to generic resource→File resolution
    }

    /**
     * Find the directory or jar a given resource has been loaded from.
     */
    public static File getResourceSource(ClassLoader c, String resource) {
        if (c == null) {
            c = Locator.class.getClassLoader();              // Class.getClassLoader(): loader that loaded Locator itself
        }
        URL url = null;
        if (c == null) {
            url = ClassLoader.getSystemResource(resource);   // ClassLoader.getSystemResource(...): uses system class loader
        } else {
            url = c.getResource(resource);                   // ClassLoader.getResource(...): looks up resource in given loader
        }
        if (url != null) {
            String u = url.toString();
            if (u.startsWith("jar:file:")) {
                int pling = u.indexOf("!");
                String jarName = u.substring(4, pling);
                return new File(fromURI(jarName));           // new File(...): wraps the resolved jar path returned by fromURI(...)
            } else if (u.startsWith("file:")) {
                int tail = u.indexOf(resource);
                String dirName = u.substring(0, tail);
                return new File(fromURI(dirName));           // new File(...): wraps the directory path returned by fromURI(...)
            }
        }
        return null;
    }

    /**
     * Constructs a file path from a <code>file:</code> URI.
     *
     * <p>Will be an absolute path if the given URI is absolute.</p>
     */
    public static String fromURI(String uri) {
        Class uriClazz = null;                               // uriClazz: java.net.URI class, loaded reflectively when available
        try {
            uriClazz = Class.forName("java.net.URI");        // Class.forName(...): checks if java.net.URI exists (JDK >= 1.4)
        } catch (ClassNotFoundException cnfe) {
            // older JDK, will fall back to manual URL/path handling below
        }

        // First branch: prefer java.net.URI + File(URI) on modern JDKs
        if (uriClazz != null && uri.startsWith("file:/")) {
            try {
                java.lang.reflect.Method createMethod =
                    uriClazz.getMethod("create", new Class[] {String.class}); // URI.create(String): factory for URI instances
                Object uriObj = createMethod.invoke(null, new Object[] {uri}); // invoke static create(...) via reflection
                java.lang.reflect.Constructor fileConst =
                    File.class.getConstructor(new Class[] {uriClazz});        // File(URI): File constructor taking a URI
                File f = (File) fileConst.newInstance(new Object[] {uriObj});
                return f.getAbsolutePath();                                   // File.getAbsolutePath(): normalized platform path
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable e2 = e.getTargetException();
                if (e2 instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) e2;      // invalid URI, propagate standard IllegalArgumentException
                } else {
                    e2.printStackTrace();                     // unexpected target exception, continue with fallback
                }
            } catch (Exception e) {
                e.printStackTrace();                          // reflection problems, fallback to URL-based branch
            }
        }

        // Second branch: generic URL parsing for "file:" URIs
        URL url = null;
        try {
            url = new URL(uri);                               // new URL(...): parses the string into protocol/host/path
        } catch (MalformedURLException emYouEarlEx) {
            // ignore; validation happens right after
        }
        if (url == null || !("file".equals(url.getProtocol()))) {
            throw new IllegalArgumentException("Can only handle valid file: URIs");
        }

        StringBuffer buf = new StringBuffer(url.getHost());   // StringBuffer (JDK): builds UNC prefix based on host
        if (buf.length() > 0) {
            buf.insert(0, File.separatorChar).insert(0, File.separatorChar); // builds "\\host" if host part is present
        }
        String file = url.getFile();
        int queryPos = file.indexOf('?');
        buf.append((queryPos < 0) ? file : file.substring(0, queryPos));

        uri = buf.toString().replace('/', File.separatorChar); // normalizes "/" into the platform-specific file separator

        // Third branch: Windows drive/path normalization (e.g. \C:\path → C:\path)
        if (File.pathSeparatorChar == ';'
            && uri.startsWith("\\")
            && uri.length() > 2
            && Character.isLetter(uri.charAt(1))
            && uri.lastIndexOf(':') > -1) {
            uri = uri.substring(1);
        }

        String path = null;
        try {
            path = decodeUri(uri);                           // decodeUri(...): converts %xx sequences back into characters
            String cwd = System.getProperty("user.dir");     // System.getProperty("user.dir"): current working directory
            int posi = cwd.indexOf(":");
            if ((posi > 0) && path.startsWith(File.separator)) {
               path = cwd.substring(0, posi + 1) + path;     // on Windows, reuse drive letter if path root lacks it
            }
        } catch (UnsupportedEncodingException exc) {
            throw new IllegalStateException("Could not convert URI to path: "
                                            + exc.getMessage());
        }
        return path;
    }

    /**
     * Decodes an Uri with % characters.
     */
    public static String decodeUri(String uri) throws UnsupportedEncodingException {
        if (uri.indexOf('%') == -1) {
            return uri;                                      // shortcut: no escape sequence present
        }
        ByteArrayOutputStream sb =
            new ByteArrayOutputStream(uri.length());         // ByteArrayOutputStream: accumulates decoded bytes
        CharacterIterator iter =
            new StringCharacterIterator(uri);                // StringCharacterIterator: iterates character-by-character over the String

        for (char c = iter.first(); c != CharacterIterator.DONE; c = iter.next()) {
            if (c == '%') {
                // Read two hexadecimal digits after '%'
                char c1 = iter.next();
                if (c1 != CharacterIterator.DONE) {
                    int i1 = Character.digit(c1, 16);
                    char c2 = iter.next();
                    if (c2 != CharacterIterator.DONE) {
                        int i2 = Character.digit(c2, 16);
                        sb.write((char) ((i1 << 4) + i2));   // combines two hex digits into a single byte
                    }
                }
            } else {
                sb.write(c);                                 // plain character, written directly as a byte
            }
        }
        return sb.toString(URI_ENCODING);                    // converts the bytes back into a String using UTF-8
    }

    /**
     * Encodes an Uri with % characters.
     */
    public static String encodeURI(String path) throws UnsupportedEncodingException {
        int i = 0;
        int len = path.length();
        int ch = 0;
        StringBuffer sb = null;                              // StringBuffer: lazily created only if something must be escaped

        // First, handle only the ASCII prefix (chars < 128)
        for (; i < len; i++) {
            ch = path.charAt(i);
            if (ch >= 128) {                                 // encountered non-ASCII, move to the UTF-8 branch
                break;
            }
            if (gNeedEscaping[ch]) {
                if (sb == null) {
                    sb = new StringBuffer(path.substring(0, i)); // copies prefix that did not need escaping
                }
                sb.append('%');
                sb.append(gAfterEscaping1[ch]);
                sb.append(gAfterEscaping2[ch]);             // uses precomputed table for the two hex digits
            } else if (sb != null) {
                sb.append((char) ch);                        // already building escaped String, just append char
            }
        }

        // Non-ASCII segment: encode in UTF-8 and escape byte by byte
        if (i < len) {
            if (sb == null) {
                sb = new StringBuffer(path.substring(0, i));
            }
            byte[] bytes = path.substring(i).getBytes(URI_ENCODING); // String.getBytes(UTF-8): obtains bytes for the tail substring
            len = bytes.length;

            for (i = 0; i < len; i++) {
                byte b = bytes[i];
                if (b < 0) {
                    ch = b + 256;
                    sb.append('%');
                    sb.append(gHexChs[ch >> 4]);
                    sb.append(gHexChs[ch & 0xf]);           // escapes non-ASCII bytes explicitly
                } else if (gNeedEscaping[b]) {
                    sb.append('%');
                    sb.append(gAfterEscaping1[b]);
                    sb.append(gAfterEscaping2[b]);          // escapes ASCII bytes marked in gNeedEscaping
                } else {
                    sb.append((char) b);                    // safe byte, converted directly to char
                }
            }
        }
        return sb == null ? path : sb.toString();           // if nothing was escaped, return the original path
    }

    /**
     * Convert a File to a URL.
     */
    public static URL fileToURL(File file)
        throws MalformedURLException {
        try {
            // File.toURL(): produces a "file:" URL; encodeURI(...) fixes spaces, '#', etc. to %xx form
            return new URL(encodeURI(file.toURL().toString()));
        } catch (UnsupportedEncodingException ex) {
            throw new MalformedURLException(ex.toString());
        }
    }

    /**
     * Get the File necessary to load the Sun compiler tools.
     */
    public static File getToolsJar() {
        boolean toolsJarAvailable = false;
        try {
            Class.forName("com.sun.tools.javac.Main");       // Class.forName(...): tries to find modern compiler on classpath
            toolsJarAvailable = true;
        } catch (Exception e) {
            try {
                Class.forName("sun.tools.javac.Main");       // tries legacy Sun compiler class on classpath
                toolsJarAvailable = true;
            } catch (Exception e2) {
                // no compiler classes found on classpath, will try to locate tools.jar manually
            }
        }
        if (toolsJarAvailable) {
            return null;                                     // compiler already available, no separate tools.jar needed
        }

        String javaHome = System.getProperty("java.home");   // "java.home": root of the JRE/JDK in use
        File toolsJar = new File(javaHome + "/lib/tools.jar");
        if (toolsJar.exists()) {
            return toolsJar;                                 // found tools.jar directly under java.home/lib
        }
        if (javaHome.toLowerCase(Locale.US).endsWith(File.separator + "jre")) {
            javaHome = javaHome.substring(0, javaHome.length() - 4); // go up from .../jre to the JDK root
            toolsJar = new File(javaHome + "/lib/tools.jar");
        }
        if (!toolsJar.exists()) {
            System.out.println("Unable to locate tools.jar. "
                 + "Expected to find it in " + toolsJar.getPath());
            return null;
        }
        return toolsJar;
    }

    /**
     * Get an array of URLs representing all of the jar files in the
     * given location.
     */
    public static URL[] getLocationURLs(File location)
         throws MalformedURLException {
        return getLocationURLs(location, new String[]{".jar"}); // calls generic overload requesting only ".jar" files
    }

    /**
     * Get an array of URLs representing all of the files of a given set of
     * extensions in the given location.
     */
    public static URL[] getLocationURLs(File location,
                                        final String[] extensions)
         throws MalformedURLException {
        URL[] urls = new URL[0];

        if (!location.exists()) {
            return urls;                                     // path/file does not exist, nothing to return
        }
        if (!location.isDirectory()) {
            // Single file case: if the extension matches, return a 1-element URL array.
            urls = new URL[1];
            String path = location.getPath();
            for (int i = 0; i < extensions.length; ++i) {
                if (path.toLowerCase().endsWith(extensions[i])) {
                    urls[0] = fileToURL(location);          // fileToURL(...): converts the single File into an URL
                    break;
                }
            }
            return urls;
        }

        // Directory case: list only files that end with one of the provided extensions
        File[] matches = location.listFiles(
            new FilenameFilter() {                           // FilenameFilter: used by File.listFiles(...)
                public boolean accept(File dir, String name) {
                    for (int i = 0; i < extensions.length; ++i) {
                        if (name.toLowerCase().endsWith(extensions[i])) {
                            return true;                     // accepts files with any of the required extensions
                        }
                    }
                    return false;
                }
            });

        urls = new URL[matches.length];
        for (int i = 0; i < matches.length; ++i) {
            urls[i] = fileToURL(matches[i]);                 // each matching File is converted into the appropriate URL
        }
        return urls;
    }
}
