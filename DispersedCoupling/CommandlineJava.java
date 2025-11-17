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

package org.apache.tools.ant.types;

import org.apache.tools.ant.BuildException;          // BuildException: Ant-specific failure reporting for configuration/runtime errors
import org.apache.tools.ant.Project;                 // Project: external build model hosting properties, paths and tasks
import org.apache.tools.ant.util.JavaEnvUtils;       // JavaEnvUtils: helper for resolving JRE executable and active Java version

import java.util.Enumeration;                        // Enumeration: legacy iterator used by Properties, Vector, etc.
import java.util.LinkedList;                         // LinkedList: ordered list to accumulate VM arguments
import java.util.List;                               // List: abstraction for argument collections
import java.util.ListIterator;                       // ListIterator: mutable cursor used for incremental command construction
import java.util.Properties;                         // Properties: key/value configuration for JVM system properties
import java.util.Vector;                             // Vector: synchronized collection used by Environment for variables

/**
 * A representation of a Java command line that is
 * a composite of 2 <tt>Commandline</tt>s. One is used for the
 * vm/options and one for the classname/arguments. It provides
 * specific methods for a Java command line.
 *
 */
public class CommandlineJava implements Cloneable {

    /**
     * commands to the JVM
     */
    private Commandline vmCommand = new Commandline();       // vmCommand: holds executable and low-level JVM flags

    /**
     * actual java commands
     */
    private Commandline javaCommand = new Commandline();     // javaCommand: holds main class/jar and program arguments

    /**
     * properties to add using -D
     */
    private SysProperties sysProperties = new SysProperties(); // SysProperties: wrapper around System properties passed as -D

    private Path classpath = null;                           // Path: Ant path abstraction for classpath
    private Path bootclasspath = null;                       // Path: separate boot classpath, applied with -Xbootclasspath
    private String vmVersion;
    private String maxMemory = null;

    /**
     *  any assertions to make? Currently only supported in forked JVMs
     */
    private Assertions assertions = null;                    // Assertions: external description of -ea/-da switches

    /**
     * Indicate whether it will execute a jar file or not, in this case
     * the first vm option must be a -jar and the 'executable' is a jar file.
     */
     private boolean executeJar = false;

    /**
     * Whether system properties and bootclasspath shall be cloned.
     * @since Ant 1.7
     */
    private boolean cloneVm = false;

    /**
     * Specialized Environment class for System properties.
     */
    public static class SysProperties extends Environment implements Cloneable {
        // CheckStyle:VisibilityModifier OFF - bc
        /** the system properties. */
        Properties sys = null;                              // sys: cached snapshot of process-wide System properties
        // CheckStyle:VisibilityModifier ON
        private Vector propertySets = new Vector();          // propertySets: grouped external property definitions to merge

        /**
         * Get the properties as an array; this is an override of the
         * superclass, as it evaluates all the properties.
         * @return the array of definitions; may be null.
         * @throws BuildException on error.
         */
        public String[] getVariables() throws BuildException {

            List definitions = new LinkedList();             // definitions: merged "-Dkey=value" style entries
            ListIterator list = definitions.listIterator();
            addDefinitionsToList(list);
            if (definitions.size() == 0) {
                return null;
            } else {
                return (String[]) definitions.toArray(new String[definitions.size()]);
            }
        }

        /**
         * Add all definitions (including property sets) to a list.
         * @param listIt list iterator supporting add method.
         */
        public void addDefinitionsToList(ListIterator listIt) {
            String[] props = super.getVariables();           // Environment.getVariables(): existing key=value variables
            if (props != null) {
                for (int i = 0; i < props.length; i++) {
                    listIt.add("-D" + props[i]);             // encode Environment variables as JVM -D arguments
                }
            }
            Properties propertySetProperties = mergePropertySets(); // merge PropertySet-provided external properties
            for (Enumeration e = propertySetProperties.keys();
                 e.hasMoreElements();) {
                String key = (String) e.nextElement();
                String value = propertySetProperties.getProperty(key);
                listIt.add("-D" + key + "=" + value);        // attach each external property as dedicated -D flag
            }
        }

        /**
         * Get the size of the sysproperties instance. This merges all
         * property sets, so is not an O(1) operation.
         * @return the size of the sysproperties instance.
         */
        public int size() {
            Properties p = mergePropertySets();              // compute effective set by aggregating all PropertySet entries
            return variables.size() + p.size();
        }

        /**
         * Cache the system properties and set the system properties to the
         * new values.
         * @throws BuildException if Security prevented this operation.
         */
        public void setSystem() throws BuildException {
            try {
                sys = System.getProperties();                // snapshot current System properties, for later restoration
                Properties p = new Properties();
                for (Enumeration e = sys.propertyNames(); e.hasMoreElements();) {
                    String name = (String) e.nextElement();
                    p.put(name, sys.getProperty(name));      // copy each existing JVM property into a new backing store
                }
                p.putAll(mergePropertySets());               // merge in external PropertySet-based configuration
                for (Enumeration e = variables.elements(); e.hasMoreElements();) {
                    Environment.Variable v = (Environment.Variable) e.nextElement();
                    v.validate();                            // external sanity check for provided variable mapping
                    p.put(v.getKey(), v.getValue());         // overlay explicit Environment.Variable entries
                }
                System.setProperties(p);                     // apply new properties globally to this JVM process
            } catch (SecurityException e) {
                throw new BuildException("Cannot modify system properties", e);
            }
        }

        /**
         * Restore the system properties to the cached value.
         * @throws BuildException  if Security prevented this operation, or
         * there were no system properties to restore.
         */
        public void restoreSystem() throws BuildException {
            if (sys == null) {
                throw new BuildException("Unbalanced nesting of SysProperties");
            }

            try {
                System.setProperties(sys);                   // restore original System properties back into JVM
                sys = null;                                  // mark snapshot as consumed
            } catch (SecurityException e) {
                throw new BuildException("Cannot modify system properties", e);
            }
        }

        /**
         * Create a deep clone.
         * @return a cloned instance of SysProperties.
         * @exception CloneNotSupportedException for signature.
         */
        public Object clone() throws CloneNotSupportedException {
            try {
                SysProperties c = (SysProperties) super.clone();
                c.variables = (Vector) variables.clone();    // cloned view of Environment variables
                c.propertySets = (Vector) propertySets.clone(); // cloned collection of external property sets
                return c;
            } catch (CloneNotSupportedException e) {
                return null;
            }
        }

        /**
         * Add a propertyset to the total set.
         * @param ps the new property set.
         */
        public void addSyspropertyset(PropertySet ps) {
            propertySets.addElement(ps);                     // register PropertySet that contributes external properties
        }

        /**
         * Add a propertyset to the total set.
         * @param ps the new property set.
         * @since Ant 1.6.3
         */
        public void addSysproperties(SysProperties ps) {
            variables.addAll(ps.variables);                  // aggregate Environment-style variables from peer instance
            propertySets.addAll(ps.propertySets);            // aggregate PropertySet groups from peer instance
        }

        /**
         * Merge all property sets into a single Properties object.
         * @return the merged object.
         */
        private Properties mergePropertySets() {
            Properties p = new Properties();
            for (Enumeration e = propertySets.elements();
                 e.hasMoreElements();) {
                PropertySet ps = (PropertySet) e.nextElement(); // PropertySet: deferred lookup of project-scoped properties
                p.putAll(ps.getProperties());                // merge resolved set into consolidated Properties view
            }
            return p;
        }

    }

    /**
     * Constructor uses the VM we are running on now.
     */
    public CommandlineJava() {
        setVm(JavaEnvUtils.getJreExecutable("java"));        // query JavaEnvUtils for current "java" executable path
        setVmversion(JavaEnvUtils.getJavaVersion());         // store active VM version as reported by runtime environment
    }

    /**
     * Create a new argument to the java program.
     * @return an argument to be configured.
     */
    public Commandline.Argument createArgument() {
        return javaCommand.createArgument();                 // delegate program argument creation to javaCommand wrapper
    }

    /**
     * Create a new JVM argument.
     * @return an argument to be configured.
     */
    public Commandline.Argument createVmArgument() {
        return vmCommand.createArgument();                   // delegate VM argument creation (-X, -D etc.) to vmCommand
    }

    /**
     * Add a system property.
     * @param sysp a property to be set in the JVM.
     */
    public void addSysproperty(Environment.Variable sysp) {
        sysProperties.addVariable(sysp);                     // treat Environment.Variable as one more -D entry
    }

    /**
     * Add a set of system properties.
     * @param sysp a set of properties.
     */
    public void addSyspropertyset(PropertySet sysp) {
        sysProperties.addSyspropertyset(sysp);               // capture PropertySet block for later merge into -D list
    }

    /**
     * Add a set of system properties.
     * @param sysp a set of properties.
     * @since Ant 1.6.3
     */
    public void addSysproperties(SysProperties sysp) {
        sysProperties.addSysproperties(sysp);                // merge externally prepared SysProperties instance
    }

    /**
     * Set the executable used to start the new JVM.
     * @param vm the executable to use.
     */
    public void setVm(String vm) {
        vmCommand.setExecutable(vm);                         // configure VM executable (e.g. "java" or custom wrapper)
    }

    /**
     * Set the JVM version required.
     * @param value the version required.
     */
    public void setVmversion(String value) {
        vmVersion = value;                                   // hint for version-sensitive options (e.g., -mx vs -Xmx)
    }

    /**
     * Set whether system properties will be copied to the cloned VM--as
     * well as the bootclasspath unless you have explicitly specified
     * a bootclasspath.
     * @param cloneVm if true copy the system properties.
     * @since Ant 1.7
     */
    public void setCloneVm(boolean cloneVm) {
        this.cloneVm = cloneVm;                              // toggle behavior to reuse caller JVM configuration baseline
    }

    /**
     * Get the current assertions.
     * @return assertions or null.
     */
    public Assertions getAssertions() {
        return assertions;
    }

    /**
     * Add an assertion set to the command.
     * @param assertions assertions to make.
     */
    public void setAssertions(Assertions assertions) {
        this.assertions = assertions;                        // attach externally built assertion configuration object
    }

    /**
     * Set a jar file to execute via the -jar option.
     * @param jarpathname the pathname of the jar to execute.
     */
    public void setJar(String jarpathname) {
        javaCommand.setExecutable(jarpathname);              // treat jar file as "executable" entity for -jar launches
        executeJar = true;
    }

    /**
     * Get the name of the jar to be run.
     * @return the pathname of the jar file to run via -jar option
     * or <tt>null</tt> if there is no jar to run.
     * @see #getClassname()
     */
    public String getJar() {
        if (executeJar) {
            return javaCommand.getExecutable();              // reuse javaCommand executable slot as jar path when -jar is set
        }
        return null;
    }

    /**
     * Set the classname to execute.
     * @param classname the fully qualified classname.
     */
    public void setClassname(String classname) {
        javaCommand.setExecutable(classname);                // switch execution mode back to main class invocation
        executeJar = false;
    }

    /**
     * Get the name of the class to be run.
     * @return the name of the class to run or <tt>null</tt> if there is no class.
     * @see #getJar()
     */
    public String getClassname() {
        if (!executeJar) {
            return javaCommand.getExecutable();              // return fully-qualified main class when in class mode
        }
        return null;
    }

    /**
     * Create a classpath.
     * @param p the project to use to create the path.
     * @return a path to be configured.
     */
    public Path createClasspath(Project p) {
        if (classpath == null) {
            classpath = new Path(p);                         // Path constructed with Project context for property expansion
        }
        return classpath;
    }

    /**
     * Create a boot classpath.
     * @param p the project to use to create the path.
     * @return a path to be configured.
     * @since Ant 1.6
     */
    public Path createBootclasspath(Project p) {
        if (bootclasspath == null) {
            bootclasspath = new Path(p);                     // separate Path dedicated to low-level bootstrap classes
        }
        return bootclasspath;
    }

    /**
     * Get the vm version.
     * @return the vm version.
     */
    public String getVmversion() {
        return vmVersion;
    }

    /**
     * Get the command line to run a Java vm.
     * @return the list of all arguments necessary to run the vm.
     */
    public String[] getCommandline() {
        //create the list
        List commands = new LinkedList();                    // commands: ordered representation of the full Java invocation
        final ListIterator listIterator = commands.listIterator();
        //fill it
        addCommandsToList(listIterator);                     // let internal builder append VM + program arguments
        //convert to an array
        return (String[]) commands.toArray(new String[commands.size()]);
    }

    /**
     * Add all the commands to a list identified by the iterator passed in.
     * @param listIterator an iterator that supports the add method.
     * @since Ant 1.6
     */
    private void addCommandsToList(final ListIterator listIterator) {
        //create the command to run Java, including user specified options
        getActualVMCommand().addCommandToList(listIterator); // start with configured VM executable and flags

        // properties are part of the vm options...
        sysProperties.addDefinitionsToList(listIterator);    // then append every -D property configuration

        if (isCloneVm()) {
            SysProperties clonedSysProperties = new SysProperties();
            PropertySet ps = new PropertySet();              // PropertySet: special view over live System properties
            PropertySet.BuiltinPropertySetName sys =
                new PropertySet.BuiltinPropertySetName();
            sys.setValue("system");                          // instruct PropertySet to mirror full system property set
            ps.appendBuiltin(sys);
            clonedSysProperties.addSyspropertyset(ps);       // stage System properties as explicit -D flags
            clonedSysProperties.addDefinitionsToList(listIterator);
        }

        //boot classpath
        Path bcp = calculateBootclasspath(true);             // derive effective boot classpath, honoring cloneVm and flags
        if (bcp.size() > 0) {
            listIterator.add("-Xbootclasspath:" + bcp.toString());
        }

        //main classpath
        if (haveClasspath()) {
            listIterator.add("-classpath");
            listIterator.add(
                    classpath.concatSystemClasspath("ignore").toString()); // combine custom path with current runtime classpath
        }

        //now any assertions are added
        if (getAssertions() != null) {
            getAssertions().applyAssertions(listIterator);   // map assertion configuration to appropriate -ea/-da switches
        }

        // JDK usage command line says that -jar must be the first option, as there is
        // a bug in JDK < 1.4 that forces the jvm type to be specified as the first
        // option, it is appended here as specified in the docs even though there is
        // in fact no order.
        if (executeJar) {
            listIterator.add("-jar");                        // toggle execution mode from main class to jar entry point
        }

        // this is the classname to run as well as its arguments.
        // in case of 'executeJar', the executable is a jar file.
        javaCommand.addCommandToList(listIterator);          // finally append target main (class or jar) and its parameters
    }

    /**
     * Specify max memory of the JVM.
     * -mx or -Xmx depending on VM version.
     * @param max the string to pass to the jvm to specifiy the max memory.
     */
    public void setMaxmemory(String max) {
        this.maxMemory = max;                                // raw numeric suffix, e.g. "512m" or "2g"
    }

    /**
     * Get a string description.
     * @return the command line as a string.
     */
    public String toString() {
        return Commandline.toString(getCommandline());       // reuse Commandline utility to render full invocation as text
    }

    /**
     * Return a String that describes the command and arguments suitable for
     * verbose output before a call to <code>Runtime.exec(String[])<code>.
     * @return the description string.
     * @since Ant 1.5
     */
    public String describeCommand() {
        return Commandline.describeCommand(getCommandline()); // human-readable representation for logging/debugging
    }

    /**
     * Return a String that describes the java command and arguments
     * for in-VM executions.
     *
     * <p>The class name is the executable in this context.</p>
     * @return the description string.
     * @since Ant 1.5
     */
    public String describeJavaCommand() {
        return Commandline.describeCommand(getJavaCommand()); // only main-class side of the command, used for in-VM runs
    }

    /**
     * Get the VM command parameters, including memory settings.
     * @return the VM command parameters.
     */
    protected Commandline getActualVMCommand() {
        Commandline actualVMCommand = (Commandline) vmCommand.clone(); // defensive clone: base VM flags remain reusable
        if (maxMemory != null) {
            if (vmVersion.startsWith("1.1")) {
                actualVMCommand.createArgument().setValue("-mx" + maxMemory);   // legacy memory flag for old VMs
            } else {
                actualVMCommand.createArgument().setValue("-Xmx" + maxMemory);  // standard -Xmx for modern JVMs
            }
        }
        return actualVMCommand;
    }

    /**
     * Get the size of the java command line. This is a fairly intensive
     * operation, as it has to evaluate the size of many components.
     * @return the total number of arguments in the java command line.
     * @see #getCommandline()
     * @deprecated since 1.7.
     *             Please dont use this, it effectively creates the
     *             entire command.
     */
    public int size() {
        int size = getActualVMCommand().size() + javaCommand.size()
            + sysProperties.size();
        // cloned system properties
        if (isCloneVm()) {
            size += System.getProperties().size();           // System.getProperties(): count of implicit -D when cloning VM
        }
        // classpath is "-classpath <classpath>" -> 2 args
        if (haveClasspath()) {
            size += 2;
        }
        // bootclasspath is "-Xbootclasspath:<classpath>" -> 1 arg
        if (calculateBootclasspath(true).size() > 0) {
            size++;
        }
        // jar execution requires an additional -jar option
        if (executeJar) {
            size++;
        }
        //assertions take up space too
        if (getAssertions() != null) {
            size += getAssertions().size();
        }
        return size;
    }

    /**
     * Get the Java command to be used.
     * @return the java command--not a clone.
     */
    public Commandline getJavaCommand() {
        return javaCommand;
    }

    /**
     * Get the VM command, including memory.
     * @return A deep clone of the instance's VM command, with memory settings added.
     */
    public Commandline getVmCommand() {
        return getActualVMCommand();
    }

    /**
     * Get the classpath for the command.
     * @return the classpath or null.
     */
    public Path getClasspath() {
        return classpath;
    }

    /**
     * Get the boot classpath.
     * @return boot classpath or null.
     */
    public Path getBootclasspath() {
        return bootclasspath;
    }

    /**
     * Cache current system properties and set them to those in this
     * Java command.
     * @throws BuildException  if Security prevented this operation.
     */
    public void setSystemProperties() throws BuildException {
        sysProperties.setSystem();                           // delegate global System property update to SysProperties
    }

    /**
     * Restore the cached system properties.
     * @throws BuildException  if Security prevented this operation, or
     * there was no system properties to restore
     */
    public void restoreSystemProperties() throws BuildException {
        sysProperties.restoreSystem();                       // restore previous global System property snapshot
    }

    /**
     * Get the system properties object.
     * @return The system properties object.
     */
    public SysProperties getSystemProperties() {
        return sysProperties;
    }

    /**
     * Deep clone the object.
     * @return a CommandlineJava object.
     * @throws BuildException if anything went wrong.
     * @throws CloneNotSupportedException never.
     */
    public Object clone() throws CloneNotSupportedException {
        try {
            CommandlineJava c = (CommandlineJava) super.clone();
            c.vmCommand = (Commandline) vmCommand.clone();          // independent copy of VM-side arguments
            c.javaCommand = (Commandline) javaCommand.clone();      // independent copy of application-side arguments
            c.sysProperties = (SysProperties) sysProperties.clone(); // cloned SysProperties with its own variable lists
            if (classpath != null) {
                c.classpath = (Path) classpath.clone();             // copy Path so further mutations are isolated
            }
            if (bootclasspath != null) {
                c.bootclasspath = (Path) bootclasspath.clone();
            }
            if (assertions != null) {
                c.assertions = (Assertions) assertions.clone();     // assertions: duplicated configuration for cloned instance
            }
            return c;
        } catch (CloneNotSupportedException e) {
            throw new BuildException(e);
        }
    }

    /**
     * Clear out the java arguments.
     */
    public void clearJavaArgs() {
        javaCommand.clearArgs();                             // reset only program arguments while leaving VM config intact
    }

    /**
     * Determine whether the classpath has been specified, and whether it shall
     * really be used or be nulled by build.sysclasspath.
     * @return true if the classpath is to be used.
     * @since Ant 1.6
     */
    protected boolean haveClasspath() {
        Path fullClasspath = classpath != null
            ? classpath.concatSystemClasspath("ignore") : null; // combine declared path with system classpath if requested
        return fullClasspath != null
            && fullClasspath.toString().trim().length() > 0;
    }

    /**
     * Determine whether the bootclasspath has been specified, and whether it
     * shall really be used (build.sysclasspath could be set or the VM may not
     * support it).
     *
     * @param log whether to log a warning if a bootclasspath has been
     * specified but will be ignored.
     * @return true if the bootclasspath is to be used.
     * @since Ant 1.6
     */
    protected boolean haveBootclasspath(boolean log) {
        return calculateBootclasspath(log).size() > 0;       // delegate to calculateBootclasspath to honor VM/version rules
    }

    /**
     * Calculate the bootclasspath based on the bootclasspath
     * specified, the build.sysclasspath and ant.build.clonevm magic
     * properties as well as the cloneVm attribute.
     * @param log whether to write messages to the log.
     * @since Ant 1.7
     */
    private Path calculateBootclasspath(boolean log) {
        if (vmVersion.startsWith("1.1")) {
            if (bootclasspath != null && log) {
                bootclasspath.log("Ignoring bootclasspath as "
                                  + "the target VM doesn't support it."); // annotate ignored setting through Path logging
            }
        } else {
            if (bootclasspath != null) {
                return bootclasspath.concatSystemBootClasspath(isCloneVm()
                                                               ? "last"
                                                               : "ignore"); // optionally append system boot classpath at the end
            } else if (isCloneVm()) {
                return Path.systemBootClasspath;             // reuse global system boot classpath when cloning VM
            }
        }
        return new Path(null);                               // empty Path: caller sees size() == 0
    }

    /**
     * Find out whether either of the cloneVm attribute or the magic property
     * ant.build.clonevm has been set.
     * @return <code>boolean</code>.
     * @since 1.7
     */
    private boolean isCloneVm() {
        return cloneVm
            || "true".equals(System.getProperty("ant.build.clonevm")); // System.getProperty: external toggle for clone behavior
    }
}
