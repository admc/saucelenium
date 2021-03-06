/*
 * Created on Jun 12, 2006
 *
 */
package org.openqa.selenium;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Properties;
import java.util.Vector;
import java.util.logging.Logger;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.Property;
import org.apache.tools.ant.types.EnumeratedAttribute;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Mapper;
import org.apache.tools.ant.util.FileNameMapper;
import org.apache.tools.ant.util.SourceFileScanner;

/**
 * Provides an Ant task to run the Selenium Translator, which translates HTML Selenese into
 * other programming languages.
 *  
 * <h3>Parameters</h3>
 * <table border="1" cellpadding="2" cellspacing="0">
 * 
 *  <tr> <td> <B>Attribute</B> </td> <td> <B>Description</B> </td> <td> <B>Required</B> </td> </tr>
 *  <tr> <td>destDir</td> <td>Location to write the translated files</td> <td>Yes</td> </tr>
 *  <tr> <td>baseUrl</td> <td>The baseUrl to use for testing</td> <td>Yes</td> </tr>
 *  <tr> <td>formatter</td> <td>Formatter to use; currently supported formatters are "java-rc", "cs-rc", "perl-rc", "python-rc", and "ruby-rc".</td> <td>Yes</td> </tr>
 *  </table>
 *  <h3>Parameters as Nested Elements</h3>
 *  <h4>fileset</h4>
 *  
 *  <p>A <a href="http://ant.apache.org/manual/CoreTypes/fileset.html">fileset</a> of HTML Selenese files to translate</p>
 * 
 *  <h4>option</h4>
 *  
 *  <p>An option to pass to the translator, in the form &lt;option name="foo" value="bar" /&gt;</p>
 *  
 *  <h4>mapper</h4>
 *  
 *  <p>A <a href="http://ant.apache.org/manual/CoreTypes/mapper.html">mapper</a> of files to output files.  The default mapper is a glob mapper from *.html to the appropriate extension for the specified formatter (.java, .cs, .pl, etc.).</p>
 *  @author danielf
 *
 */
public class XlatorTask extends Task {

    private Vector<FileSet> _filesets = new Vector<FileSet>();
    private HashMap<String, String> options = new HashMap<String, String>();
    private Mapper mapperElement;
    private File destDir;
    private FormatterType formatter;
    private URL baseUrl;
    
    public XlatorTask() {
        super();
        
    }
    
    /** Specifies a destination directory for translated output */
    public void setDestDir(File destDir) {
        this.destDir = destDir;
    }    
    
    public void setFormatter(FormatterType formatter) {
        this.formatter = formatter;
    }
    
    public void setBaseUrl(URL baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void addFileSet(FileSet fs) {
        _filesets.addElement(fs);
    }
    
    public void addConfiguredOption(Property p) {
        options.put(p.getName(), p.getValue());
    }
    
    /**
     * Defines the mapper to map source to destination files.
     * @return a mapper to be configured
     * @exception BuildException if more than one mapper is defined
     */
    public Mapper createMapper() throws BuildException {
        if (mapperElement != null) {
            throw new BuildException("Cannot define more than one mapper",
                                     getLocation());
        }
        mapperElement = new Mapper(getProject());
        return mapperElement;
    }
    
    // The default mapper maps *.html -> *.java, or *.cs, etc.
    private void createDefaultMapper() {
        if (mapperElement != null) return;
        assert formatter != null;
        String extension = formatter.getExtension();
        createMapper();
        assert mapperElement != null;
        Mapper.MapperType t = new Mapper.MapperType();
        t.setValue("glob");
        mapperElement.setType(t);
        mapperElement.setFrom("*.html");
        mapperElement.setTo("*." + extension);
    }
    
    public void execute() throws BuildException {
        checkPreconditions();
        FileNameMapper mapper = mapperElement.getImplementation();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new AntLogHandler(getProject(), this));
        
        // Loop through all nested filesets, looking for files to translate
        for (int i = 0; i < _filesets.size(); i++) {
            FileSet fs = _filesets.elementAt(i);
            DirectoryScanner ds = fs.getDirectoryScanner(getProject());
            String[] files = ds.getIncludedFiles();
            File d = fs.getDir(getProject());
            SourceFileScanner scanner = new SourceFileScanner(this);
            files = scanner.restrict(files, d, destDir, mapper);
            if (files.length > 0) {
                log("Handling " + files.length + " files from "
                    + d.getAbsolutePath());
                for (String fileName : files) {
                    translateFile(mapper, d, fileName, log);
                }
            }
        }
    }
    
    private void checkPreconditions() throws BuildException {
        if (_filesets.size() == 0) {
            throw new BuildException("You must specify at least one fileset!");
        }
        if (formatter == null) {
            throw new BuildException("You must specify a formatter!");
        }
        if (destDir == null) {
            throw new BuildException("You must specify a destDir!");
        }
        if (!destDir.exists()) {
            throw new BuildException("destDir doesn't exist: " + destDir.getAbsolutePath());
        }
        createDefaultMapper();
    }

    private void translateFile(FileNameMapper mapper, File srcDir, String fileName, Logger log) {
        File input = new File(srcDir, fileName);
        String htmlSource;
        String output;
        try {
            log("Reading " + input.getAbsolutePath(), Project.MSG_DEBUG);
            htmlSource = Xlator.loadFile(input);
            log("Translating " + input, Project.MSG_INFO);
            output = Xlator.xlateTestCase(Xlator.extractTestName(input), baseUrl.toString(), formatter.getValue(), htmlSource, options, log);
        } catch (Exception e) {
            throw new BuildException(e);
        }
        String[] outputFileNames = mapper.mapFileName(fileName);
        for (String outputFileName : outputFileNames) {
            File outputFile = new File(destDir, outputFileName);
            try {
                log("Writing " + outputFile.getAbsolutePath(), Project.MSG_DEBUG);
                writeFile(outputFile, output);
            } catch (IOException e) {
                throw new BuildException(e);
            }
        }
    }
    
    static void writeFile(File outputFile, String text) throws IOException {
        outputFile.getParentFile().mkdirs();
        final FileWriter out = new FileWriter(outputFile);
        try {
            out.write(text);
        } finally {
            out.close();
        }
    }
    
    /*
     * By creating this enumerated attribute, Ant will make sure the user defines
     * a valid formatter name.  Plus, we get to specify a default extension for
     * the output files.
     */
    public static class FormatterType extends EnumeratedAttribute {
        private static Properties formatters;
        private static String[] values;

        static {
            formatters = new Properties();
            formatters.put("java-rc",
                                "java");
            formatters.put("java-rc-testng",
                                "java");
            formatters.put("cs-rc",
                                "cs");
            formatters.put("perl-rc",
                                "pl");
            formatters.put("python-rc",
                                "py");
            formatters.put("ruby-rc",
                                "rb");
            Vector<String> keys = new Vector<String>();
            for (Object o : formatters.keySet()) {
                keys.add((String) o);
            }
            values = keys.toArray(new String[keys.size()]);
        }
        
        public FormatterType() {}

        public String[] getValues() {
            return values;
        }

        public String getExtension() {
            return formatters.getProperty(getValue());
        }
    }
}
