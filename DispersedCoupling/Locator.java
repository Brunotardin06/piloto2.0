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

import java.net.MalformedURLException;
import java.net.URL;
import java.io.File;
import java.io.FilenameFilter;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.Locale;

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
    public static final String URI_ENCODING = "UTF-8";

    private static boolean[] gNeedEscaping = new boolean[128];
    private static char[] gAfterEscaping1 = new char[128];
    private static char[] gAfterEscaping2 = new char[128];
    private static char[] gHexChs = {'0', '1', '2', '3', '4', '5', '6', '7',
                                     '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};

    static {
        for (int i = 0; i <= 0x1f; i++) {
            gNeedEscaping[i] = true;
            gAfterEscaping1[i] = gHexChs[i >> 4];
            gAfterEscaping2[i] = gHexChs[i & 0xf];
        }
        gNeedEscaping[0x7f] = true;
        gAfterEscaping1[0x7f] = '7';
        gAfterEscaping2[0x7f] = 'F';
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
    }

    /**
     * Find the directory or jar file the class has been loaded from.
     */
    public static File getClassSource(Class c) {
        String classResource = c.getName().replace('.', '/') + ".class";
        return getResourceSource(c.getClassLoader(), classResource); // reuses generic resource resolution
    }

    /**
     * Find the directory or jar a given resource has been loaded from.
     */
    public static File getResourceSource(ClassLoader c, String resource) {
        if (c == null) {
            c = Locator.class.getClassLoader(); // fallback: class loader of Locator
        }
        URL url = null;
        if (c == null) {
            url = ClassLoader.getSystemResource(resource); // consult system class loader
        } else {
            url = c.getResource(resource);                 // consult provided class loader
        }
        if (url != null) {
            String u = url.toString();
            if (u.startsWith("jar:file:")) {
                int pling = u.indexOf("!");
                String jarName = u.substring(4, pling);
                return new File(fromURI(jarName));         // map jar URI to local jar File
            } else if (u.startsWith("file:")) {
                int tail = u.indexOf(resource);
                String dirName = u.substring(0, tail);
                return new File(fromURI(dirName));         // map file: prefix to local directory path
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
        Class uriClazz = null; // reflection: check if java.net.URI is available
        try {
            uriClazz = Class.forName("java.net.URI");
        } catch (ClassNotFoundException cnfe) {
            // older JDK, will fall back to manual path handling below
        }

        // first branch: use java.net.URI + File(URI) when possible
        if (uriClazz != null && uri.startsWith("file:/")) {
            try {
                java.lang.reflect.Method createMethod
                    = uriClazz.getMethod("create", new Class[] {String.class}); // URI.create(String) via reflection
                Object uriObj = createMethod.invoke(null, new Object[] {uri});   // build URI instance dynamically
                java.lang.reflect.Constructor fileConst
                    = File.class.getConstructor(new Class[] {uriClazz});        // File(URI) constructor
                File f = (File) fileConst.newInstance(new Object[] {uriObj});
                return f.getAbsolutePath();                                      // let File normalize OS-specific path
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable e2 = e.getTargetException();
                if (e2 instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) e2; // report invalid URI from the standard API
                } else {
                    e2.printStackTrace(); // unexpected target exception
                }
            } catch (Exception e) {
                e.printStackTrace();       // reflection problems, fall back to URL-based logic
            }
        }

        // second branch: generic URL parsing for file: URIs
        URL url = null;
        try {
            url = new URL(uri); // interpret String as URL (file:, http:, etc.)
        } catch (MalformedURLException emYouEarlEx) {
            // ignore; validation happens below
        }
        if (url == null || !("file".equals(url.getProtocol()))) {
            throw new IllegalArgumentException("Can only handle valid file: URIs");
        }

        StringBuffer buf = new StringBuffer(url.getHost()); // keep host (e.g., UNC host in Windows)
        if (buf.length() > 0) {
            buf.insert(0, File.separatorChar).insert(0, File.separatorChar); // build \\host\ prefix
        }
        String file = url.getFile();
        int queryPos = file.indexOf('?');
        buf.append((queryPos < 0) ? file : file.substring(0, queryPos));

        uri = buf.toString().replace('/', File.separatorChar); // use platform file separator instead of '/'

        // third branch: Windows drive/path normalization
        if (File.pathSeparatorChar == ';'
            && uri.startsWith("\\")
            && uri.length() > 2
            && Character.isLetter(uri.charAt(1))
            && uri.lastIndexOf(':') > -1) {
            uri = uri.substring(1); // fix UNC-like prefix \C:\... to C:\...
        }

        String path = null;
        try {
            path = decodeUri(uri);                         // decode %xx sequences into characters
            String cwd = System.getProperty("user.dir");   // current working directory from environment
            int posi = cwd.indexOf(":");
            if ((posi > 0) && path.startsWith(File.separator)) {
               path = cwd.substring(0, posi + 1) + path;   // reuse drive letter if path is rooted but lacks drive
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
            return uri; // no escaping present
        }
        ByteArrayOutputStream sb = new ByteArrayOutputStream(uri.length()); // accumulates decoded bytes
        CharacterIterator iter = new StringCharacterIterator(uri);          // iterates over characters
        for (char c = iter.first(); c != CharacterIterator.DONE;
             c = iter.next()) {
            if (c == '%') {
                char c1 = iter.next();
                if (c1 != CharacterIterator.DONE) {
                    int i1 = Character.digit(c1, 16);
                    char c2 = iter.next();
                    if (c2 != CharacterIterator.DONE) {
                        int i2 = Character.digit(c2, 16);
                        sb.write((char) ((i1 << 4) + i2));                 // combine two hex digits into one byte
                    }
                }
            } else {
                sb.write(c);                                               // plain character, write as-is
            }
        }
        return sb.toString(URI_ENCODING);                                   // rebuild String using UTF-8
    }

    /**
     * Encodes an Uri with % characters.
     */
    public static String encodeURI(String path) throws UnsupportedEncodingException {
        int i = 0;
        int len = path.length();
        int ch = 0;
        StringBuffer sb = null; // lazily allocated builder for escaped form
        for (; i < len; i++) {
            ch = path.charAt(i);
            if (ch >= 128) { // first non-ASCII triggers UTF-8 branch
                break;
            }
            if (gNeedEscaping[ch]) {
                if (sb == null) {
                    sb = new StringBuffer(path.substring(0, i)); // copy unmodified prefix
                }
                sb.append('%');
                sb.append(gAfterEscaping1[ch]);
                sb.append(gAfterEscaping2[ch]); // use precomputed escape table
            } else if (sb != null) {
                sb.append((char) ch);
            }
        }

        // non-ASCII part: encode in UTF-8 and escape as needed
        if (i < len) {
            if (sb == null) {
                sb = new StringBuffer(path.substring(0, i));
            }
            byte[] bytes = path.substring(i).getBytes(URI_ENCODING); // UTF-8 bytes of the remaining substring
            len = bytes.length;

            for (i = 0; i < len; i++) {
                byte b = bytes[i];
                if (b < 0) {
                    ch = b + 256;
                    sb.append('%');
                    sb.append(gHexChs[ch >> 4]);
                    sb.append(gHexChs[ch & 0xf]);  // escape non-ASCII bytes explicitly
                } else if (gNeedEscaping[b]) {
                    sb.append('%');
                    sb.append(gAfterEscaping1[b]);
                    sb.append(gAfterEscaping2[b]);
                } else {
                    sb.append((char) b);
                }
            }
        }
        return sb == null ? path : sb.toString();
    }

    /**
     * Convert a File to a URL.
     */
    public static URL fileToURL(File file)
        throws MalformedURLException {
        try {
            return new URL(encodeURI(file.toURL().toString())); // File.toURL() + encodeURI to fix characters like space and '#'
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
            Class.forName("com.sun.tools.javac.Main");  // probe modern compiler class in classpath
            toolsJarAvailable = true;
        } catch (Exception e) {
            try {
                Class.forName("sun.tools.javac.Main");  // probe legacy compiler class
                toolsJarAvailable = true;
            } catch (Exception e2) {
                // compiler classes not found, try locating tools.jar
            }
        }
        if (toolsJarAvailable) {
            return null; // compiler already on classpath, no extra jar needed
        }

        String javaHome = System.getProperty("java.home");   // base JRE/JDK directory
        File toolsJar = new File(javaHome + "/lib/tools.jar");
        if (toolsJar.exists()) {
            return toolsJar; // found directly under java.home/lib
        }
        if (javaHome.toLowerCase(Locale.US).endsWith(File.separator + "jre")) {
            javaHome = javaHome.substring(0, javaHome.length() - 4); // go up from .../jre to JDK root
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
        return getLocationURLs(location, new String[]{".jar"}); // reuse generic method with .jar filter
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
            return urls; // nothing to scan
        }
        if (!location.isDirectory()) {
            urls = new URL[1];
            String path = location.getPath();
            for (int i = 0; i < extensions.length; ++i) {
                if (path.toLowerCase().endsWith(extensions[i])) {
                    urls[0] = fileToURL(location); // single matching file becomes the only URL
                    break;
                }
            }
            return urls;
        }

        File[] matches = location.listFiles(
            new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    for (int i = 0; i < extensions.length; ++i) {
                        if (name.toLowerCase().endsWith(extensions[i])) {
                            return true; // accept files with any of the required extensions
                        }
                    }
                    return false;
                }
            });

        urls = new URL[matches.length];
        for (int i = 0; i < matches.length; ++i) {
            urls[i] = fileToURL(matches[i]); // convert each matched file into a URL
        }
        return urls;
    }
}
