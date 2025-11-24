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

import org.apache.tools.ant.BuildException;   // BuildException (Ant): reports configuration/runtime failures to the build
import org.apache.tools.ant.Project;          // Project (Ant): build context used when creating Path and resolving properties
import org.apache.tools.ant.util.JavaEnvUtils; // JavaEnvUtils (Ant): resolves current JRE executable and Java version

import java.util.LinkedList;                  // LinkedList (JDK): backing list that stores the assembled command segments
import java.util.List;                        // List (JDK): abstraction for the full JVM + program command
import java.util.ListIterator;                // ListIterator (JDK): cursor used while incrementally appending arguments
import java.util.Properties;                  // Properties (JDK): concrete storage for System properties / key-value pairs
import java.util.Vector;                      // Vector (JDK): collection used by Environment/SysProperties to hold variables
import java.util.Enumeration;                 // Enumeration (JDK): legacy iteration API used by Properties and Vector

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
    private Commandline vmCommand = new Commandline();   // Commandline (Ant): builds JVM launcher + VM-level flags

    /**
     * actual java commands
     */
    private Commandline javaCommand = new Commandline(); // Commandline (Ant): builds main class/jar + program arguments

    /**
     * properties to add using -D
     */
    private SysProperties sysProperties = new SysProperties(); // SysProperties (inner): wraps Environment/PropertySet into -D options

    private Path classpath = null;                       // Path (Ant): represents the application classpath for this command
    private Path bootclasspath = null;                   // Path (Ant): represents the low-level VM boot classpath
    private String vmVersion;
    private String maxMemory = null;

    /**
     *  any assertions to make? Currently only supported in forked JVMs
     */
    private Assertions assertions = null;                // Assertions (Ant): describes which -ea/-da assertion switches to apply

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
        Properties sys = null;                          // Properties (JDK): cached snapshot of JVM-wide System properties
        // CheckStyle:VisibilityModifier ON
        private Vector propertySets = new Vector();     // Vector (JDK): holds PropertySet instances to be merged into Properties

        /**
         * Get the properties as an array; this is an override of the
         * superclass, as it evaluates all the properties.
         * @return the array of definitions; may be null.
         * @throws BuildException on error.
         */
        public String[] getVariables() throws BuildException {

            List definitions = new LinkedList();         // List (JDK): collects all "-Dkey=value" style definitions
            ListIterator list = definitions.listIterator();
            addDefinitionsToList(list);                 // SysProperties.addDefinitionsToList(...) expands Environment + PropertySet
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
            String[] props = super.getVariables();      // Environment.getVariables(): returns "key=value" pairs for variables
            if (props != null) {
                for (int i = 0; i < props.length; i++) {
                    listIt.add("-D" + props[i]);        // converts each Environment variable into a JVM -D argument
                }
            }
            Properties propertySetProperties = mergePropertySets(); // merges all PropertySet-backed properties into one Properties
            for (Enumeration e = propertySetProperties.keys();
                 e.hasMoreElements();) {
                String key = (String) e.nextElement();
                String value = propertySetProperties.getProperty(key);
                listIt.add("-D" + key + "=" + value);   // each PropertySet entry becomes its own "-Dkey=value" on the command line
            }
        }

        /**
         * Get the size of the sysproperties instance. This merges all
         * property sets, so is not an O(1) operation.
         * @return the size of the sysproperties instance.
         */
        public int size() {
            Properties p = mergePropertySets();         // merge PropertySet data before counting effective entries
            return variables.size() + p.size();
        }

        /**
         * Cache the system properties and set the system properties to the
         * new values.
         * @throws BuildException if Security prevented this operation.
         */
        public void setSystem() throws BuildException {
            try {
                sys = System.getProperties();           // System.getProperties(): captures current process-wide properties
                Properties p = new Properties();
                for (Enumeration e = sys.propertyNames(); e.hasMoreElements();) {
                    String name = (String) e.nextElement();
                    p.put(name, sys.getProperty(name)); // copies each existing property into the new backing Properties
                }
                p.putAll(mergePropertySets());          // overlays properties coming from all configured PropertySet instances
                for (Enumeration e = variables.elements(); e.hasMoreElements();) {
                    Environment.Variable v = (Environment.Variable) e.nextElement(); // Environment.Variable (Ant): single key/value
                    v.validate();                       // Environment.Variable.validate(): checks the mapping before use
                    p.put(v.getKey(), v.getValue());    // explicit Environment variables override previous values in p
                }
                System.setProperties(p);                // System.setProperties(): installs new Properties for the whole JVM
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
                System.setProperties(sys);              // System.setProperties(): restores original snapshot captured in setSystem()
                sys = null;                             // null marks that there is no longer a cached snapshot to restore
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
                c.variables = (Vector) variables.clone();      // Vector.clone(): copies Environment.Variable entries
                c.propertySets = (Vector) propertySets.clone(); // Vector.clone(): copies PropertySet references
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
            propertySets.addElement(ps);                // PropertySet (Ant): contributes a dynamic subset of Project properties
        }

        /**
         * Add a propertyset to the total set.
         * @param ps the new property set.
         * @since Ant 1.6.3
         */
        public void addSysproperties(SysProperties ps) {
            variables.addAll(ps.variables);             // merges Environment-style variables from another SysProperties instance
            propertySets.addAll(ps.propertySets);       // merges PropertySet sources from another SysProperties instance
        }

        /**
         * Merge all property sets into a single Properties object.
         * @return the merged object.
         */
        private Properties mergePropertySets() {
            Properties p = new Properties();
            for (Enumeration e = propertySets.elements();
                 e.hasMoreElements();) {
                PropertySet ps = (PropertySet) e.nextElement(); // PropertySet (Ant): resolves to a Properties view at runtime
                p.putAll(ps.getProperties());              // merges each PropertySet's Properties into the consolidated result
            }
            return p;
        }

    }

    /**
     * Constructor uses the VM we are running on now.
     */
    public CommandlineJava() {
        setVm(JavaEnvUtils.getJreExecutable("java"));    // JavaEnvUtils.getJreExecutable(...): picks the 'java' binary for this JRE
        setVmversion(JavaEnvUtils.getJavaVersion());     // JavaEnvUtils.getJavaVersion(): records current VM version string
    }

    /**
     * Create a new argument to the java program.
     * @return an argument to be configured.
     */
    public Commandline.Argument createArgument() {
        return javaCommand.createArgument();             // Commandline.createArgument(): adds one program-level argument slot
    }

    /**
     * Create a new JVM argument.
     * @return an argument to be configured.
     */
    public Commandline.Argument createVmArgument() {
        return vmCommand.createArgument();               // Commandline.createArgument(): adds one VM-level argument slot (-X, -D, etc.)
    }

    /**
     * Add a system property.
     * @param sysp a property to be set in the JVM.
     */
    public void addSysproperty(Environment.Variable sysp) {
        sysProperties.addVariable(sysp);                 // Environment.addVariable(): registers one Environment.Variable to be exported
    }

    /**
     * Add a set of system properties.
     * @param sysp a set of properties.
     */
    public void addSyspropertyset(PropertySet sysp) {
        sysProperties.addSyspropertyset(sysp);           // SysProperties.addSyspropertyset(): includes one more PropertySet source
    }

    /**
     * Add a set of system properties.
     * @param sysp a set of properties.
     * @since Ant 1.6.3
     */
    public void addSysproperties(SysProperties sysp) {
        sysProperties.addSysproperties(sysp);            // SysProperties.addSysproperties(): merges another SysProperties configuration
    }

    /**
     * Set the executable used to start the new JVM.
     * @param vm the executable to use.
     */
    public void setVm(String vm) {
        vmCommand.setExecutable(vm);                     // Commandline.setExecutable(): defines which JVM launcher binary to invoke
    }

    /**
     * Set the JVM version required.
     * @param value the version required.
     */
    public void setVmversion(String value) {
        vmVersion = value;                               // stored VM version string used by getActualVMCommand() to choose flags
    }

    /**
     * Set whether system properties will be copied to the cloned VM--as
     * well as the bootclasspath unless you have explicitly specified
     * a bootclasspath.
     * @param cloneVm if true copy the system properties.
     * @since Ant 1.7
     */
    public void setCloneVm(boolean cloneVm) {
        this.cloneVm = cloneVm;                          // flag used by isCloneVm() and calculateBootclasspath(...) to decide behavior
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
        this.assertions = assertions;                    // stores Assertions (Ant) configuration to be applied later in addCommandsToList
    }

    /**
     * Set a jar file to execute via the -jar option.
     * @param jarpathname the pathname of the jar to execute.
     */
    public void setJar(String jarpathname) {
        javaCommand.setExecutable(jarpathname);          // Commandline.setExecutable(): now points to the JAR that will be run
        executeJar = true;                               // flag tells addCommandsToList(...) to prepend "-jar" before javaCommand
    }

    /**
     * Get the name of the jar to be run.
     * @return the pathname of the jar file to run via -jar option
     * or <tt>null</tt> if there is no jar to run.
     * @see #getClassname()
     */
    public String getJar() {
        if (executeJar) {
            return javaCommand.getExecutable();          // returns the JAR path previously stored in javaCommand.setExecutable(...)
        }
        return null;
    }

    /**
     * Set the classname to execute.
     * @param classname the fully qualified classname.
     */
    public void setClassname(String classname) {
        javaCommand.setExecutable(classname);            // Commandline.setExecutable(): now points to the main class instead of a JAR
        executeJar = false;                              // disables -jar mode so class name is used directly
    }

    /**
     * Get the name of the class to be run.
     * @return the name of the class to run or <tt>null</tt> if there is no class.
     * @see #getJar()
     */
    public String getClassname() {
        if (!executeJar) {
            return javaCommand.getExecutable();          // returns fully qualified main class configured in setClassname(...)
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
            classpath = new Path(p);                     // Path(Path.Project): uses Project context to resolve property-based entries
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
            bootclasspath = new Path(p);                 // Path(Path.Project): dedicated to VM boot classpath configuration
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
        List commands = new LinkedList();                // LinkedList (JDK): holds every token of the final "java ..." command
        final ListIterator listIterator = commands.listIterator();
        //fill it
        addCommandsToList(listIterator);                 // CommandlineJava.addCommandsToList(...): orchestrates all collaborators
        //convert to an array
        return (String[]) commands.toArray(new String[commands.size()]);
    }

    /**
     * Add all the commands to a list identified by the iterator passed in.
     * @param listIterator an iterator that supports the add method.
     * @since Ant 1.6
     */
    private void addCommandsToList(final ListIterator listIterator) {
        // create the command to run Java, including user specified options
        getActualVMCommand().addCommandToList(listIterator); // Commandline.addCommandToList(vmCommand): adds JVM executable + VM options

        // properties are part of the vm options...
        sysProperties.addDefinitionsToList(listIterator);    // SysProperties.addDefinitionsToList(...): expands Environment/PropertySet into -D flags

        if (isCloneVm()) {
            SysProperties clonedSysProperties = new SysProperties(); // SysProperties: new container for cloned System properties
            PropertySet ps = new PropertySet();              // PropertySet (Ant): describes which Project properties to expose
            PropertySet.BuiltinPropertySetName sys =
                new PropertySet.BuiltinPropertySetName();    // BuiltinPropertySetName (Ant): selects a predefined property group
            sys.setValue("system");                          // "system": instructs PropertySet to pull from current System properties
            ps.appendBuiltin(sys);                           // PropertySet.appendBuiltin(...): attaches the "system" source
            clonedSysProperties.addSyspropertyset(ps);       // SysProperties.addSyspropertyset(...): includes that PropertySet
            clonedSysProperties.addDefinitionsToList(listIterator); // expands those System properties into additional "-D..." entries
        }

        // boot classpath
        Path bcp = calculateBootclasspath(true);             // Path returned by calculateBootclasspath(...): effective boot classpath
        if (bcp.size() > 0) {
            listIterator.add("-Xbootclasspath:" + bcp.toString()); // adds JVM boot classpath option built by Path.concatSystemBootClasspath(...)
        }

        // main classpath
        if (haveClasspath()) {
            listIterator.add("-classpath");
            listIterator.add(
                    classpath.concatSystemClasspath("ignore").toString()); // Path.concatSystemClasspath(...): merges custom + system classpath
        }

        // now any assertions are added
        if (getAssertions() != null) {
            getAssertions().applyAssertions(listIterator);   // Assertions.applyAssertions(...): contributes -ea/-da flags to the command
        }

        // JDK usage command line says that -jar must be the first option, as there is
        // a bug in JDK < 1.4 that forces the jvm type to be specified as the first
        // option, it is appended here as specified in the docs even though there is
        // in fact no order.
        if (executeJar) {
            listIterator.add("-jar");                        // literal "-jar": switches JVM into JAR-execution mode
        }

        // this is the classname to run as well as its arguments.
        // in case of 'executeJar', the executable is a jar file.
        javaCommand.addCommandToList(listIterator);          // Commandline.addCommandToList(javaCommand): adds main class/JAR + program args
    }

    /**
     * Specify max memory of the JVM.
     * -mx or -Xmx depending on VM version.
     * @param max the string to pass to the jvm to specifiy the max memory.
     */
    public void setMaxmemory(String max) {
        this.maxMemory = max;                                // stored as raw suffix (e.g. "512m") and translated to -mx/-Xmx later
    }

    /**
     * Get a string description.
     * @return the command line as a string.
     */
    public String toString() {
        return Commandline.toString(getCommandline());       // Commandline.toString(...): renders full command as a single String
    }

    /**
     * Return a String that describes the command and arguments suitable for
     * verbose output before a call to <code>Runtime.exec(String[])<code>.
     * @return the description string.
     * @since Ant 1.5
     */
    public String describeCommand() {
        return Commandline.describeCommand(getCommandline()); // Commandline.describeCommand(...): produces a human-readable description
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
        return Commandline.describeCommand(getJavaCommand()); // focuses only on javaCommand (main class/JAR + args) for in-VM runs
    }

    /**
     * Get the VM command parameters, including memory settings.
     * @return the VM command parameters.
     */
    protected Commandline getActualVMCommand() {
        Commandline actualVMCommand = (Commandline) vmCommand.clone(); // Commandline.clone(): copies current VM executable + flags
        if (maxMemory != null) {
            if (vmVersion.startsWith("1.1")) {
                actualVMCommand.createArgument().setValue("-mx" + maxMemory);   // old VMs: uses -mx for max heap size
            } else {
                actualVMCommand.createArgument().setValue("-Xmx" + maxMemory);  // newer VMs: uses -Xmx for max heap size
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
            size += System.getProperties().size();           // System.getProperties().size(): counts extra -D entries when cloning VM
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
        // assertions take up space too
        if (getAssertions() != null) {
            size += getAssertions().size();                  // Assertions.size(): returns number of tokens it will add to the command
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
        sysProperties.setSystem();                           // SysProperties.setSystem(): swaps global System properties for this JVM
    }

    /**
     * Restore the cached system properties.
     * @throws BuildException  if Security prevented this operation, or
     * there was no system properties to restore
     */
    public void restoreSystemProperties() throws BuildException {
        sysProperties.restoreSystem();                       // SysProperties.restoreSystem(): restores previously cached System properties
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
            c.vmCommand = (Commandline) vmCommand.clone();          // Commandline.clone(): independent copy of VM-side configuration
            c.javaCommand = (Commandline) javaCommand.clone();      // Commandline.clone(): independent copy of program-side configuration
            c.sysProperties = (SysProperties) sysProperties.clone(); // SysProperties.clone(): copies environment variables and property sets
            if (classpath != null) {
                c.classpath = (Path) classpath.clone();             // Path.clone(): new Path with same entries for classpath
            }
            if (bootclasspath != null) {
                c.bootclasspath = (Path) bootclasspath.clone();     // Path.clone(): new Path with same entries for bootclasspath
            }
            if (assertions != null) {
                c.assertions = (Assertions) assertions.clone();     // Assertions.clone(): copies assertion rules for the new instance
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
        javaCommand.clearArgs();                             // Commandline.clearArgs(): removes only program arguments, not executable
    }

    /**
     * Determine whether the classpath has been specified, and whether it shall
     * really be used or be nulled by build.sysclasspath.
     * @return true if the classpath is to be used.
     * @since Ant 1.6
     */
    protected boolean haveClasspath() {
        Path fullClasspath = classpath != null
            ? classpath.concatSystemClasspath("ignore") : null; // Path.concatSystemClasspath(...): optionally merges system classpath
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
        return calculateBootclasspath(log).size() > 0;       // delegates to calculateBootclasspath(...) to apply VM/version rules
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
                                  + "the target VM doesn't support it."); // Path.log(...): emits warning that bootclasspath will be ignored
            }
        } else {
            if (bootclasspath != null) {
                return bootclasspath.concatSystemBootClasspath(isCloneVm()
                                                               ? "last"
                                                               : "ignore"); // Path.concatSystemBootClasspath(...): merges with system boot classpath
            } else if (isCloneVm()) {
                return Path.systemBootClasspath;             // Path.systemBootClasspath (Ant): shared static representing system boot classpath
            }
        }
        return new Path(null);                               // returns an empty Path instance when no boot classpath will be used
    }

    /**
     * Find out whether either of the cloneVm attribute or the magic property
     * ant.build.clonevm has been set.
     * @return <code>boolean</code>.
     * @since 1.7
     */
    private boolean isCloneVm() {
        return cloneVm
            || "true".equals(System.getProperty("ant.build.clonevm")); // System.getProperty(...): external toggle to clone VM settings
    }
}
