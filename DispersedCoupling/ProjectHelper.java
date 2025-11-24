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

package org.apache.tools.ant;

import java.io.BufferedReader;          // BufferedReader: incremental reading of service descriptors
import java.io.File;                    // File: bridge to filesystem-based build files
import java.io.InputStream;             // InputStream: raw resource stream for service discovery
import java.io.InputStreamReader;       // InputStreamReader: charset-aware wrapper over service stream
import java.util.Hashtable;             // Hashtable: legacy map for project properties
import java.util.Locale;                // Locale: normalized case-insensitive comparisons
import java.util.Vector;                // Vector: legacy list used in parsing helpers

import org.apache.tools.ant.helper.ProjectHelper2; // ProjectHelper2: default concrete XML parser for Ant projects
import org.apache.tools.ant.util.LoaderUtils;      // LoaderUtils: helper for resolving context class loaders
import org.xml.sax.AttributeList;       // AttributeList: external SAX representation of XML attributes

/**
 * Configures a Project (complete with Targets and Tasks) based on
 * an XML build file. It relies on a pluggable helper to do the actual
 * processing of the XML file.
 *
 * This class also provides static helpers for common introspection and
 * configuration patterns used by Ant's XML parsing layer.
 */
public class ProjectHelper {
    /** The URI for Ant core namespace. */
    public static final String ANT_CORE_URI    = "antlib:org.apache.tools.ant";

    /** The URI for antlib current definitions. */
    public static final String ANT_CURRENT_URI = "ant:current";

    /** The URI prefix for defined types/tasks - format is antlib:&lt;package&gt;. */
    public static final String ANTLIB_URI      = "antlib:";

    /** Polymorphic attribute name used to disambiguate component type. */
    public static final String ANT_TYPE = "ant-type";

    /**
     * Name of JVM system property which provides the name of the
     * ProjectHelper class to use.
     */
    public static final String HELPER_PROPERTY =
        "org.apache.tools.ant.ProjectHelper";

    /**
     * The service identifier in jars which provide ProjectHelper
     * implementations.
     */
    public static final String SERVICE_ID =
        "META-INF/services/org.apache.tools.ant.ProjectHelper";

    /**
     * Name of ProjectHelper reference that will be added to a Project
     * once the helper has been chosen.
     */
    public static final String PROJECTHELPER_REFERENCE = "ant.projectHelper";

    /**
     * Stack of import/include sources used during parsing.
     * Used to keep track of imported files so that error reporting
     * can display the import path.
     */
    private Vector importStack = new Vector(); // importStack: tracks nested includes/imports for error tracing

    /**
     * Configures the project with the contents of the specified XML file.
     *
     * @param project The project to configure. Must not be <code>null</code>.
     * @param buildFile An XML file giving the project's configuration.
     *                  Must not be <code>null</code>.
     *
     * @exception BuildException if the configuration is invalid or cannot
     *                           be read
     */
    public static void configureProject(Project project, File buildFile)
        throws BuildException {
        ProjectHelper helper = ProjectHelper.getProjectHelper(); // getProjectHelper: resolve pluggable helper implementation
        project.addReference(PROJECTHELPER_REFERENCE, helper);   // register helper instance as a project-scoped reference
        helper.parse(project, buildFile);                        // delegate XML parsing and configuration to helper
    }

    public ProjectHelper() {
    }

    /**
     * Import stack.
     * Used to keep track of imported files. Error reporting should
     * display the import path.
     *
     * @return the stack of import source objects.
     */
    public Vector getImportStack() {
        return importStack;
    }

    // --------------------  Parse method  --------------------

    /**
     * Parses the project file, configuring the project as it goes.
     *
     * @param project The project for the resulting ProjectHelper to configure.
     *                Must not be <code>null</code>.
     * @param source The source for XML configuration. A helper must support
     *               at least File, for backward compatibility. Helpers may
     *               support URL, InputStream, etc. or specialized types.
     *
     * @since Ant 1.5
     * @exception BuildException if the configuration is invalid or cannot
     *                           be read
     */
    public void parse(Project project, Object source) throws BuildException {
        throw new BuildException("ProjectHelper.parse() must be implemented "
            + "in a helper plugin " + this.getClass().getName()); // message hints to concrete plugin implementors
    }

    /**
     * Discovers a project helper instance. Uses the same patterns
     * as JAXP, commons-logging, etc: a system property, a JDK1.3
     * service discovery, then a default.
     *
     * @return a ProjectHelper, either a custom implementation
     * if one is available and configured, or the default implementation
     * otherwise.
     *
     * @exception BuildException if a specified helper class cannot
     * be loaded/instantiated.
     */
    public static ProjectHelper getProjectHelper() throws BuildException {
        ProjectHelper helper = null;

        // 1) System property: explicit helper configured by the environment
        String helperClass = System.getProperty(HELPER_PROPERTY);
        try {
            if (helperClass != null) {
                helper = newHelper(helperClass); // uses reflection + context ClassLoader (see LoaderUtils)
            }
        } catch (SecurityException e) {
            System.out.println("Unable to load ProjectHelper class \""
                + helperClass + "\" specified in system property "
                + HELPER_PROPERTY);
        }

        // 2) Service discovery via META-INF/services/...
        if (helper == null) {
            try {
                ClassLoader classLoader = LoaderUtils.getContextClassLoader(); // LoaderUtils: centralizes context loader access
                InputStream is = null;

                if (classLoader != null) {
                    is = classLoader.getResourceAsStream(SERVICE_ID);          // service file in application classpath
                }
                if (is == null) {
                    is = ClassLoader.getSystemResourceAsStream(SERVICE_ID);    // fallback to JVM/system classpath
                }

                if (is != null) {
                    InputStreamReader isr;
                    try {
                        isr = new InputStreamReader(is, "UTF-8");             // prefer UTF-8, as usual for service descriptors
                    } catch (java.io.UnsupportedEncodingException e) {
                        isr = new InputStreamReader(is);                      // degrade to platform default if needed
                    }
                    BufferedReader rd = new BufferedReader(isr);              // wrapper for line-oriented reading

                    String helperClassName = rd.readLine();                   // read declared ProjectHelper implementation
                    rd.close();

                    if (helperClassName != null && !"".equals(helperClassName)) {
                        helper = newHelper(helperClassName);                  // instantiate helper discovered via service file
                    }
                }
            } catch (Exception ex) {
                System.out.println("Unable to load ProjectHelper "
                    + "from service \"" + SERVICE_ID + "\"");
            }
        }

        // 3) Fallback to Ant's default XML helper
        if (helper != null) {
            return helper;
        } else {
            return new ProjectHelper2();                                      // ProjectHelper2: standard Ant build.xml parser
        }
    }

    /**
     * Creates a new helper instance from the name of the class.
     * It first tries the context class loader, then falls back to
     * Class.forName().
     *
     * @param helperClass The name of the class to create an instance
     *                    of. Must not be <code>null</code>.
     *
     * @return a new instance of the specified class.
     *
     * @exception BuildException if the class cannot be found or
     * cannot be appropriately instantiated.
     */
    private static ProjectHelper newHelper(String helperClass)
        throws BuildException {
        ClassLoader classLoader = LoaderUtils.getContextClassLoader(); // use context loader to see app-provided helpers
        try {
            Class clazz = null;
            if (classLoader != null) {
                try {
                    clazz = classLoader.loadClass(helperClass);        // attempt to load helper from application classpath
                } catch (ClassNotFoundException ex) {
                    // try next method
                }
            }
            if (clazz == null) {
                clazz = Class.forName(helperClass);                    // fallback: load via default class loader
            }
            return ((ProjectHelper) clazz.newInstance());              // reflective instantiation of helper plugin
        } catch (Exception e) {
            throw new BuildException(e);
        }
    }

    /**
     * JDK1.1 compatible access to the context class loader.
     * Cut & paste from JAXP.
     *
     * @deprecated since 1.6.x.
     *             Use LoaderUtils.getContextClassLoader()
     *
     * @return the current context class loader, or <code>null</code>
     * if the context class loader is unavailable.
     */
    public static ClassLoader getContextClassLoader() {
        if (!LoaderUtils.isContextLoaderAvailable()) {          // delegating check to LoaderUtils helper
            return null;
        }

        return LoaderUtils.getContextClassLoader();             // unified entry point for context loader access
    }

    // -------------------- Static utils, used by most helpers ----------------

    /**
     * Configures an object using an introspection helper.
     *
     * @param target The target object to be configured.
     *               Must not be <code>null</code>.
     * @param attrs  A list of attributes to configure within the target.
     *               Must not be <code>null</code>.
     * @param project The project containing the target.
     *                Must not be <code>null</code>.
     *
     * @deprecated since 1.6.x.
     *             Use IntrospectionHelper for each property.
     *
     * @exception BuildException if any of the attributes can't be handled by
     *                           the target
     */
    public static void configure(Object target, AttributeList attrs,
                                 Project project) throws BuildException {
        if (target instanceof TypeAdapter) {
            target = ((TypeAdapter) target).getProxy();         // TypeAdapter: unwraps underlying implementation to configure
        }

        IntrospectionHelper ih =
            IntrospectionHelper.getHelper(project, target.getClass()); // IntrospectionHelper: reflection-based property binder

        for (int i = 0; i < attrs.getLength(); i++) {
            String value = replaceProperties(project, attrs.getValue(i),
                                             project.getProperties()); // resolve ${...} using project-wide properties
            try {
                ih.setAttribute(project, target,
                                attrs.getName(i).toLowerCase(Locale.US), value); // map XML attribute → Java bean setter

            } catch (BuildException be) {
                // id attribute must be set externally
                if (!attrs.getName(i).equals("id")) {
                    throw be;
                }
            }
        }
    }

    /**
     * Adds the content of #PCDATA sections to an element.
     *
     * @param project The project containing the target.
     *                Must not be <code>null</code>.
     * @param target  The target object to be configured.
     *                Must not be <code>null</code>.
     * @param buf A character array of the text within the element.
     *            Will not be <code>null</code>.
     * @param start The start element in the array.
     * @param count The number of characters to read from the array.
     *
     * @exception BuildException if the target object doesn't accept text
     */
    public static void addText(Project project, Object target, char[] buf,
        int start, int count) throws BuildException {
        addText(project, target, new String(buf, start, count)); // delegate char[] → String conversion to overload
    }

    /**
     * Adds the content of #PCDATA sections to an element.
     *
     * @param project The project containing the target.
     *                Must not be <code>null</code>.
     * @param target  The target object to be configured.
     *                Must not be <code>null</code>.
     * @param text    Text to add to the target.
     *                May be <code>null</code>, in which case this
     *                method call is a no-op.
     *
     * @exception BuildException if the target object doesn't accept text
     */
    public static void addText(Project project, Object target, String text)
        throws BuildException {

        if (text == null) {
            return;
        }

        if (target instanceof TypeAdapter) {
            target = ((TypeAdapter) target).getProxy();         // unwrap adapted task/type before injecting text
        }

        IntrospectionHelper.getHelper(project, target.getClass())
                           .addText(project, target, text);     // push character data into bean via helper strategy
    }

    /**
     * Stores a configured child element within its parent object.
     *
     * @param project Project containing the objects.
     *                May be <code>null</code>.
     * @param parent  Parent object to add child to.
     *                Must not be <code>null</code>.
     * @param child   Child object to store in parent.
     *                Should not be <code>null</code>.
     * @param tag     Name of element which generated the child.
     *                May be <code>null</code>, in which case
     *                the child is not stored.
     */
    public static void storeChild(Project project, Object parent,
         Object child, String tag) {
        IntrospectionHelper ih
            = IntrospectionHelper.getHelper(project, parent.getClass()); // obtain helper bound to parent concrete type
        ih.storeElement(project, parent, child, tag);                    // delegate parent–child wiring to helper policy
    }

    /**
     * Replaces <code>${xxx}</code> style constructions in the given value with
     * the string value of the corresponding properties.
     *
     * @param project The project containing the properties to replace.
     *                Must not be <code>null</code>.
     * @param value   The string to be scanned for property references.
     *                May be <code>null</code>.
     *
     * @exception BuildException if the string contains an opening
     *                           <code>${</code> without a closing
     *                           <code>}</code>
     * @return the original string with the properties replaced, or
     *         <code>null</code> if the original string is <code>null</code>.
     *
     * @deprecated since 1.6.x.
     *             Use project.replaceProperties().
     * @since 1.5
     */
    public static String replaceProperties(Project project, String value)
            throws BuildException {
        return project.replaceProperties(value);              // delegate to Project's internal property resolution engine
    }

    /**
     * Replaces <code>${xxx}</code> style constructions in the given value
     * with the string value of the corresponding data types.
     *
     * @param project The container project. Used solely for logging.
     *                Must not be <code>null</code>.
     * @param value   The string to be scanned for property references.
     *                May be <code>null</code>, in which case this
     *                method returns immediately with no effect.
     * @param keys    Mapping (String to String) of property names to their
     *                values. Must not be <code>null</code>.
     *
     * @exception BuildException if the string contains an opening
     *                           <code>${</code> without a closing
     *                           <code>}</code>
     * @return the original string with the properties replaced, or
     *         <code>null</code> if the original string is <code>null</code>.
     * @deprecated since 1.6.x.
     *             Use PropertyHelper.
     */
    public static String replaceProperties(Project project, String value,
         Hashtable keys) throws BuildException {
        PropertyHelper ph = PropertyHelper.getPropertyHelper(project); // PropertyHelper: pluggable property resolution strategy
        return ph.replaceProperties(null, value, keys);               // perform interpolation using external property map
    }

    /**
     * Parses a string containing <code>${xxx}</code> style property
     * references into two lists. The first list is a collection
     * of text fragments, while the other is a set of string property names.
     * <code>null</code> entries in the first list indicate a property
     * reference from the second list.
     *
     * @param value        Text to parse. Must not be <code>null</code>.
     * @param fragments    List to add text fragments to.
     *                     Must not be <code>null</code>.
     * @param propertyRefs List to add property names to.
     *                     Must not be <code>null</code>.
     *
     * @deprecated since 1.6.x.
     *             Use PropertyHelper.
     * @exception BuildException if the string contains an opening
     *                           <code>${</code> without a closing
     *                           <code>}</code>
     */
    public static void parsePropertyString(String value, Vector fragments,
                                           Vector propertyRefs)
        throws BuildException {
        PropertyHelper.parsePropertyStringDefault(value, fragments,
                propertyRefs);                                  // shared default parsing routine for ${...} expressions
    }

    /**
     * Map a namespaced {uri,name} to an internal string format.
     * For backwards compatibility, names from the Ant core URI will be
     * mapped to "name"; other names will be mapped to
     * "uri:name".
     * @param uri   The namespace URI
     * @param name  The local name
     * @return      The stringified form of the namespaced name
     */
    public static String genComponentName(String uri, String name) {
        if (uri == null || uri.equals("") || uri.equals(ANT_CORE_URI)) {
            return name;                                       // core components are addressed by local name only
        }
        return uri + ":" + name;                               // namespaced components encoded as "uri:localName"
    }

    /**
     * Extract a URI from a component name.
     *
     * @param componentName  The stringified form for {uri, name}
     * @return               The URI or "" if not present
     */
    public static String extractUriFromComponentName(String componentName) {
        if (componentName == null) {
            return "";
        }
        int index = componentName.lastIndexOf(':');
        if (index == -1) {
            return "";
        }
        return componentName.substring(0, index);              // returns everything before last ':' as namespace identifier
    }

    /**
     * Extract the element name from a component name.
     *
     * @param componentName  The stringified form for {uri, name}
     * @return               The element name of the component
     */
    public static String extractNameFromComponentName(String componentName) {
        int index = componentName.lastIndexOf(':');
        if (index == -1) {
            return componentName;                              // non-namespaced name: use as is
        }
        return componentName.substring(index + 1);             // local element name after namespace separator
    }

    /**
     * Add location to a BuildException error message to provide
     * better diagnostics in nested calls.
     *
     * @param ex          the original BuildException
     * @param newLocation the location of the calling task (may be null)
     * @return a (possibly wrapped) BuildException with an augmented
     *         error message and location.
     */
    public static BuildException addLocationToBuildException(
        BuildException ex, Location newLocation) {
        if (ex.getLocation() == null || ex.getMessage() == null) {
            return ex;
        }
        String errorMessage
            = "The following error occurred while executing this line:"
            + System.getProperty("line.separator")             // query standard line.separator from environment
            + ex.getLocation().toString()
            + ex.getMessage();
        if (newLocation == null) {
            return new BuildException(errorMessage, ex);       // wrap original exception, preserving causal chain
        } else {
            return new BuildException(
                errorMessage, ex, newLocation);                // attach explicit location of caller task in error report
        }
    }
}
