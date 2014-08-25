#!/bin/env groovy

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.text.*

import javax.tools.FileObject;
  
//GroovyClassLoader loader = new GroovyClassLoader(getClass().getClassLoader());
//Class groovyUtilsClass = loader.parseClass(new File("/opt/appserv/common/binGroovy/groovyUtils.groovy"));
//groovyUtils = groovyUtilsClass.newInstance();
//println("Result is: " + groovyUtils.run("ls", true, true, false));


class GitTask {

  /** holds all config info, args, setup dirs, etc */
  GitConfig gitConfig = null;

  /** associate file name (absolute path) file object which consists of
   * where the file came from, and where it is locally */
  Map<String, GitFileObject> fileObjectMap = new TreeMap<String, GitFileObject>();

  /** lazy load the config */
  GitConfig gitConfig() {
    if (gitConfig == null) {
      gitConfig = new GitConfig(gitTask: this);
    }
    return gitConfig;
  }

  /** if the dir doesnt exist, create it and any parent dirs also... */
  void mkdirs(File dir) {

    if (!dir.exists() && !dir.mkdirs()) {
      
      throw new RuntimeException("Could not create directory : " + dir.getCanonicalPath());
      
    }
    if (!dir.isDirectory()) {
      throw new RuntimeException("Should be a directory but is not: " + dir);
    }

  }

  /**
   * see if blank
   */
  boolean isBlank(String input) {
    if (input == null) {
      return true;
    }
    input = input.trim();
    if ("".equals(input)) {
      return true;
    }
    return false;
    
  }
  
  /** run a command, perhaps failOnError, or printOnError */
  int run(String command, boolean failOnError, boolean printOnError, boolean runAsMultilineShell, boolean printOutput) {
    
    this.gitConfig.logDebug("Command: " + command);
    
    def proc;
    if (runAsMultilineShell) {
      proc = "/bin/bash -s ".execute();
      proc.out << command;
      proc.out.flush();
      proc.out.close();
    } else {
      proc = command.execute();
    }
    proc.waitFor();
    String output = proc.in.text;
    String error = proc.err.text;
    if (!isBlank(output) && printOutput) {
      println(output);
    }
    if (!isBlank(output) && !printOutput) {
      this.gitConfig.logDebug(output);
    }
     
    if (!isBlank(error) && printOutput) {
      println(error);
    }
    if (!isBlank(error) && !printOutput) {
      this.gitConfig.logDebug(error);
    }
    
    if (proc.exitValue() == 0) {
      return 0;
    }
    if (printOnError) {
      println("Non-zero exit code ${proc.exitValue()} on command: ${command}");
    }
    if (failOnError) {
      System.exit(proc.exitValue());
    }
    return proc.exitValue();
  }

  
  /** checksum a file, return the checksum (not output from md5sum) */
  String checksum(fileName) {

    //do the md5 thing...
    //[appadmin@lukes bin]$ md5sum days-since-1970.sh
    //8410e4d639a59b109c1ac893d5c27eda  days-since-1970.sh

    def md5sumProc = ("/usr/bin/md5sum " + escapeForUnixCommand(fileName)).execute()

    md5sumProc.waitFor();

    def md5out;
    if (md5sumProc.exitValue() == 0) {
      //md5out = FastExternalUtils.toString(md5sumProc.in, "ISO-8859-1");
      md5out = md5sumProc.in.text;
    }

    //we need to get the checksum part, which is the beginning part...
    def spaceIndex = md5out ? md5out.indexOf(' ') : -1;

    //5 is arbitrary, should be much larger and not negative
    if (!md5out || spaceIndex < 5) {
      println(md5out);
      println(md5sumProc.err.text);
      throw new RuntimeException("Error: could not get md5sum of file: " + fileName);
    }

    def checksum = md5out.substring(0,spaceIndex);
    return checksum;
  }

  /** this will call unix mv, so you can move files or dirs to another dir or rename or whatever */
  def move(fileNameFrom, fileNameTo) {

    def debugFlag = this.gitConfig.debugMode ? " -v " : "";
    def moveCommand = "/bin/mv " + debugFlag +  escapeForUnixCommand(fileNameFrom) + " " + escapeForUnixCommand(fileNameTo);

    def moveProc = ("/bin/bash -s ").execute();

    //cat this so that the wildcard works
    moveProc.out << moveCommand;
    moveProc.out.flush();
    moveProc.out.close();

    moveProc.waitFor();

    if (moveProc.exitValue() != 0) {

      println("Couldnt move file: " + fileNameFrom + " to: " + fileNameTo + ": " + moveCommand);
      //stdout and err
      println moveProc.in.text
      println moveProc.err.text
      System.exit(1);
    }
    if (this.gitConfig.debugMode) {
      //no println since output will print a newline
      print("DEBUG: " + moveCommand + ", ");
      println(moveProc.in.text);
    }
  }

  def escapeForUnixCommand(String someString) {
    if (someString == null) {
      return someString;
    }
    someString = someString.replace(" ", "\\ ");
    return someString;
  }
  
  /** copy one file to another, return true if copied over existing, false if created */
  def copy(fileFrom, fileTo) {
    
    if (fileFrom instanceof String) {
      fileFrom = new File(fileFrom);
    }
    if (fileTo instanceof String) {
      fileTo = new File(fileTo);
    }

    //support copying to a directory...
    if (fileTo.isDirectory()) {
      fileTo = fileTo.getCanonicalPath();
      if (!fileTo.endsWith("/") && !fileTo.endsWith("\\")) {
        fileTo += File.separator;
      }
      fileTo = new File(fileTo + fileFrom.getName());
    }

    def fileFromPath = fileFrom.getCanonicalPath();
    def fileToPath = fileTo.getCanonicalPath();
    
    def copyProc;
    def fileToExists = fileTo.exists();
    if (fileToExists) {

      copyProc = ("/bin/bash -s ").execute();

      //cat this so that the execute permissions and such dont change
      copyProc.out << ("cat " + escapeForUnixCommand(fileFromPath) + " > " + escapeForUnixCommand(fileToPath));
      copyProc.out.flush();
      copyProc.out.close();

    } else {

      if (!fileTo.getParentFile().exists()) {

        // make parent dirs
        def mkdirProc = ("/bin/mkdir -p " + escapeForUnixCommand(fileTo.getParentFile().getCanonicalFile())).execute();

        mkdirProc.waitFor();

        if (mkdirProc.exitValue() != 0) {

          println("Couldnt make parent dirs: " + fileTo.getParentFile().getCanonicalFile());
          //stdout and err
          println copyProc.in.text
          println copyProc.err.text
          System.exit(1);
    
        }
      }
    
      copyProc = ("/bin/cp -p " + escapeForUnixCommand(fileFromPath) + " " + escapeForUnixCommand(fileToPath)).execute();
    }


    copyProc.waitFor();

    if (copyProc.exitValue() != 0) {

      println("Couldnt copy file: " + fileFromPath + " to: " + fileToPath);
      //stdout and err
      println copyProc.in.text
      println copyProc.err.text
      System.exit(1);

    }
    //set the last modified since it was cat'ed and changed
    fileTo.setLastModified(fileFrom.lastModified());
    return fileToExists;
  }

  /** return a list of files inside a directory (can be dir name or dir file), isRecursive if
   * it should look in subdirs for files also */
  List<File> listFiles(dir, isRecursive) {
    this.gitConfig.logDebug("Listing files for: " + dir + ", recursive: " + isRecursive);
    dir = dir instanceof String ? new File(dir) : dir;
    List<File> result = new ArrayList();
    listFilesHelper(dir, result, isRecursive, 100, true);
    Collections.sort(result);
    return result;
  }

  /** helper method to find a list inside a directory */
  void listFilesHelper(dir, fileList, isRecursive, timeToLive, firstFile) {
    if (!dir.exists() || !dir.isDirectory()) {
      println("Dir doesnt exist or isnt a directory: " + dir.getCanonicalPath());
      System.exit(1);
    }
    if (timeToLive-- < 0) {
      println("Time to live ran out, recursion problem?");
      System.exit(1);
    }
    def files = dir.listFiles();
    
    for (file in files) {
      def directoryMetadata = file.getName() == "gitMeta____directory__.properties";
      //skip over the permissions and ownership metadata files
      if (!directoryMetadata
          && file.getName().startsWith("gitMeta__") && file.getName().endsWith(".properties")) {
        continue;
      }
      //skip over git temp files
      if (file.getName().endsWith("~") || file.getName().endsWith("~")) {
        continue;
      }
      if (file.isDirectory()) {
        if (isRecursive && file.name != ".git") {
          listFilesHelper(file, fileList, isRecursive, timeToLive, false);
        }
      } else {
        if (directoryMetadata) {
          //add the directory
          fileList.add(file.getParentFile());
        } else {
          fileList.add(file);
        }
      }
    }
  }

  /**
   * pass through to system diff command.
   * printResults is a boolean which is true to print the results of the diff command (though wont
   * print if binary extension)
   * return true if diffs, false if no diffs
   */
  def diff(first, second, printResults) {

    def diffProc = ('diff ' + escapeForUnixCommand(first) + ' ' + escapeForUnixCommand(second)).execute();

    diffProc.waitFor();

    if (printResults) {
      if (diffProc.exitValue() == 0) {
        println("There are no content changes between: " + first + " and: " + second);
      } else {
        //see if this is binary... assume not
        def isAscii = true;
        def gitgroovyFileTypes = this.gitConfig.gitgroovyFileTypes();
        for (entry in gitgroovyFileTypes.fileExtensions) {
          if (first.endsWith(entry.key)) {
            isAscii = entry.value == 'ascii';
            break;
          }
        }
        if (isAscii) {
          println(diffProc.in.text);
        }
      }
    }
    return diffProc.exitValue() != 0;
    
  }

  /** find files in a dir (or a file), and add to map as from a certain location (or whatever value).
   *  the key is the relative path under the main directory
   * serverPathPrefix is e.g. */
  def addFilesToMap(File inputFile) {

    String repoFileName = this.gitConfig.repoDir().getAbsolutePath() + inputFile;

    File repoFile = new File(repoFileName);
    
    this.gitConfig.logDebug("addFilesToMap(" + repoFileName + ", " + inputFile + ")");

    List<File> files = null;
    
    //if (repoFile.isDirectory()) {
    //  files = listFiles(repoFile, true);
    //} else {
    //  files = new ArrayList<File>();
    //  files.add(repoFile);
    //}

    files = new ArrayList<File>();
    files.add(repoFile);

    for (File file in files) {
    
      def fileName = file.getCanonicalPath();

      this.gitConfig.logDebug("addFilesToMap fileName: " + fileName);
     
      this.gitConfig.logDebug("adding to file map: fileName: '" + fileName + "',\n  key: '"
        + fileName );

      def fileObject = new GitFileObject(gitFile:file,
          gitTask: this, existsInGit: true);
      fileObject.init();
      fileObjectMap.put(fileName, fileObject);
    }
   
  }

  
  /** checkout files as the first task to do */
  def checkoutFiles() {
    def errorString = "";

    //clone or sync the repo
    //serpotsl01.seo.int/home/mchyzer/git
    //   /opt/appserv/local/git.groovy/data/repo

    File dataDir = this.gitConfig().dataDir;
    
    if (!dataDir.exists() && !dataDir.isDirectory()) {
      
      throw new RuntimeException("Cannot find data dir : " + dataDir.getCanonicalPath());
      
    }
    
    File repoParentDir = this.gitConfig().repoParentDir();
    
    mkdirs(repoParentDir);
    
    this.gitConfig.logDebug("repoParentDir is " + repoParentDir);
    
    //git clone ssh+git://serpotsl01.seo.int/opt/repo/mchyzer_test

    this.gitConfig.logDebug("repo url is " + this.gitConfig().gitUrl());

    File repoDir = this.gitConfig().repoDir();
    
    this.gitConfig.logDebug("repo dir is " + repoDir.getAbsolutePath());
    
    if (!repoDir.exists()) {

      String commands = "cd " + repoParentDir + "\n/usr/bin/git clone " + this.gitConfig().gitUrl() + "\n";
      
      this.run(commands, true, true, true, true);
  
    } else {

      if (this.gitConfig.pull) {
        this.gitConfig.logDebug("repo has already been cloned, pulling latest version");
      
        //need to sync
        String commands = "cd " + repoDir.getAbsolutePath() + "\n/usr/bin/git fetch --all\n/usr/bin/git reset --hard origin/master\n";
        
        this.run(commands, true, true, true, false);
      } else {
      this.gitConfig.logDebug("repo has already been cloned, not pulled due to --pull=false");
      
      }
    }
        
    if (!repoDir.exists()) {
      throw new RuntimeException("Repo dir doesnt exist????  " + repoDir);
    }    

    addFilesToMap(new File(this.gitConfig.inputName));
   
  }

  
  /** compare files on local server with those on server */
  def compareFiles() {
    

    for (fileObject in this.fileObjectMap.values()) {
      
      File normalOsFile = fileObject.normalOsFile();
      File localGitFile = fileObject.gitFile;

      gitConfig.logDebug("Comparing file: " + normalOsFile.getCanonicalPath()
        + ",  with: " + localGitFile.getCanonicalPath());
    
      //see if no differences
      if (!normalOsFile.exists() && !localGitFile.exists()) {
        println("File does not exist on local filesystem or in git");
      } else if (!localGitFile.exists()) {
        println("File does not exist in git");
      } else if (normalOsFile.exists() && !fileObject.diffLocalWithGit(false)) {
      
        println("No changes between " + normalOsFile.getCanonicalPath() + " and repository file: "
          + fileObject.gitFile.getAbsolutePath());

      } else {

        println((fileObject.isDirectory ? "Directory " : "File ") + fileObject.normalOsPath
          + " differs from repository file: " + fileObject.gitFile.getAbsolutePath());
        if (!fileObject.isDirectory) {
          LENGTH: {
            def localFileLength = normalOsFile.length();
            def localGitFileLength = localGitFile.length();
            def lengthRelation = (localFileLength == localGitFileLength ? "=" :
              (localFileLength > localGitFileLength ? ">" : "<"));
            println("Bytes: " + localFileLength + " " + lengthRelation  + " " + localGitFileLength);
          }
        }

        if (normalOsFile.exists()) {
          //print the diffs
          fileObject.diffLocalWithGit(true);
        } else {

          println("File: " + fileObject.normalOsPath + " does not exist on this filesystem, but *is* in git: "
            + fileObject.gitFile.getAbsolutePath());

          //just print the new file (or not since the file could be huge or binary or something)
          //def catProc = ('cat ' + fileObject.localGitFilePath()).execute();

          //catProc.waitFor();
          //println(catProc.in.text);
          //println(catProc.err.text);
        }

      }

    }
  }
  
  /** backup old copies, and get files from repository */
  def getFiles() {

    for (GitFileObject fileObject in this.fileObjectMap.values()) {

      File normalOsFile = fileObject.normalOsFile();
      File localGitFile = fileObject.gitFile;

      if (!localGitFile.exists()) {
        println("Cannot find file in repository " + localGitFile.getAbsolutePath());
        continue;
      }
      
      gitConfig.logDebug("Comparing file: " + normalOsFile.getCanonicalPath()
        + ",  with: " + localGitFile.getCanonicalPath());

      //see if no differences
      if (normalOsFile.exists() && !fileObject.diffLocalWithGit(false)) {

        println("No changes between " + normalOsFile.getCanonicalPath()
          + " and repository file: " + fileObject.gitFile.getAbsolutePath());

      } else {


        if (normalOsFile.exists()) {

          //make a backup to the temp dir
          fileObject.backupFileParentInit();
          def theBackupFilePath = fileObject.backupFilePath();
          if (!fileObject.isDirectory) {
            copy(fileObject.normalOsPath, theBackupFilePath);
          }

          println("Backed up file temporarily: " + fileObject.normalOsPath + " to: " + theBackupFilePath);
          
        } else {

          println("File: " + fileObject.normalOsPath + " not backed up since it doesnt exist");
          //make the dirs if not exist
          mkdirs(normalOsFile.getParentFile());
        }
        
        if (!fileObject.isDirectory) {
          copy(fileObject.gitFile.getAbsolutePath(), fileObject.normalOsPath);
        } else {
          if (!normalOsFile.exists()) {
            mkdirs(normalOsFile);
          }
        }

        println("Got file: " + fileObject.normalOsPath + " from git2: " + fileObject.gitFile.getAbsolutePath());
   
      }
      
      //lets register this linking in the state file if it is not already there
      fileObject.syncUpFileAssociationWithMetadata();
            
    }
  }

  /** commit to repository */
  def commitFile() {

    if (this.gitConfig.comment == null || "" == this.gitConfig.comment ) {
      println("Enter a comment (maybe in double quotes) for the commit");
      System.exit(1);
    }

    //dont get tripped up by quotes
    this.gitConfig.comment = this.gitConfig.comment.replace("\"", "^");
    this.gitConfig.comment = this.gitConfig.comment.replace("'", "^");
    
    for (fileObject in this.fileObjectMap.values()) {

      File normalOsFile = fileObject.normalOsFile();
      File localGitFile = fileObject.gitFile;

      if (!normalOsFile.exists()) {
    
        println("Local file doesnt exist: " + normalOsFile.getAbsolutePath());
        System.exit(1);

      }

      //see if no differences
      if (localGitFile.exists() && !fileObject.diffLocalWithGit(false)) {

        println("No changes between " + normalOsFile.getCanonicalPath()
          + " and repository file: " + fileObject.gitFile.getAbsolutePath());
        continue;
        
      }

      boolean needsAdd = !localGitFile.exists();

      if (!needsAdd) {
        //make a backup to the temp dir
        fileObject.backupFileParentInit();
        String theBackupFilePath = fileObject.backupFilePath();
        if (!fileObject.isDirectory) {
          copy(localGitFile, theBackupFilePath);
        }

        println("Backed up git file temporarily (its in history too) to: " + theBackupFilePath);

      } else {

        println("No need to back up git file since it was not in git, its a new file");
      
      }

      //copy to repo
      copy(fileObject.normalOsPath, localGitFile);

      if (needsAdd) {        
        //we need to add to repo
        //  git add stuff.txt
        this.run("cd " + escapeForUnixCommand(localGitFile.getParentFile().getAbsolutePath()) + "\n/usr/bin/git add " + escapeForUnixCommand(localGitFile.getName()), true, true, true, true);

      }
      
      //  git commit stuff.txt -m "adding"
      // git push origin master
      this.run("cd " + escapeForUnixCommand(localGitFile.getParentFile().getAbsolutePath()) + "\n/usr/bin/git commit " + escapeForUnixCommand(localGitFile.getName())
        + " -m '" + this.gitConfig.comment +  "'\n/usr/bin/git push origin master", true, true, true, true);
      
      //lets register this linking in the state file if it is not already there
      fileObject.syncUpFileAssociationWithMetadata();
      
    }
  }

  /** browse the repository */
  def browseFiles() {
    
    for (fileObject in this.fileObjectMap.values()) {

      File normalOsFile = fileObject.normalOsFile();
      File localGitFile = fileObject.gitFile;

      gitConfig.logDebug("Browsing file: " + localGitFile.getAbsolutePath());

      if (!localGitFile.exists()) {
        println("Cannot find file in repository " + localGitFile.getAbsolutePath());
        continue;
      }

      //lets just ls that dir, or ls from the parent dir for a file
      if (localGitFile.isDirectory()) {
        this.run("ls -lat " + escapeForUnixCommand(localGitFile.getAbsolutePath()) + " | grep -v ' .git' | grep -v ' \\.\$' | grep -v ' \\.\\.\$'", true, true, true, true);
      } else {
        this.run("cd " + escapeForUnixCommand(localGitFile.getParentFile().getAbsolutePath()) + "\nls -lat " + escapeForUnixCommand(localGitFile.getName()) + " | grep -v ' .git' | grep -v ' \\.\$' | grep -v ' \\.\\.\$'", true, true, true, true);
      }

    }
  }
  
  /** annotate a file */
  def annotateFile() {
    
    if (this.fileObjectMap.size() != 1) {
      throw new RuntimeException("Can only annotate one file at a time");
    }
    
    for (fileObject in this.fileObjectMap.values()) {

      File normalOsFile = fileObject.normalOsFile();
      File localGitFile = fileObject.gitFile;

      gitConfig.logDebug("Annotating file: " + localGitFile.getAbsolutePath());

      if (!localGitFile.exists()) {
        println("Cannot find file in repository " + localGitFile.getAbsolutePath());
        continue;
      }
      if (!localGitFile.isFile()) {
        println("Cannot annotate a directory (or can I????) " + localGitFile.getAbsolutePath());
        continue;
      }

      this.run("cd " + escapeForUnixCommand(localGitFile.getParentFile().getAbsolutePath()) + "\n/usr/bin/git blame " + escapeForUnixCommand(localGitFile.getName()), true, true, true, true);

    }
  }

  /** history a file */
  def historyFile() {
    
    if (this.fileObjectMap.size() != 1) {
      throw new RuntimeException("Can only history one file at a time");
    }
    
    for (fileObject in this.fileObjectMap.values()) {

      File normalOsFile = fileObject.normalOsFile();
      File localGitFile = fileObject.gitFile;

      gitConfig.logDebug("History file: " + localGitFile.getAbsolutePath());

      if (!localGitFile.exists()) {
        println("Cannot find file in repository " + localGitFile.getAbsolutePath());
        continue;
      }
      if (!localGitFile.isFile()) {
        println("Cannot history a directory (or can I????) " + localGitFile.getAbsolutePath());
        continue;
      }

      this.run("cd " + escapeForUnixCommand(localGitFile.getParentFile().getAbsolutePath()) + "\n/usr/bin/git log -50 " + escapeForUnixCommand(localGitFile.getName()), true, true, true, true);

    }
  }

  
  
  def execute() {
    checkoutFiles();

    if (this.gitConfig().compareFiles) {
      compareFiles();
    }
    if (this.gitConfig().getFiles) {
      getFiles();
    }
    if (this.gitConfig().browseFiles) {
      browseFiles();
    }
    if (this.gitConfig().annotateFile) {
      annotateFile();
    }
    if (this.gitConfig().historyFile) {
      historyFile();
    }
    if (this.gitConfig().commitFile) {
      commitFile();
    }
  }

}
  
GitTask gitTask = new GitTask();

//process args
gitTask.gitConfig().processArgs(this.args);
gitTask.execute();


/** identifies a file which is to be operated on by git */
class GitFileObject {
  
  /** e.g. /tmp/temp8.txt   */
  String normalOsPath;
  
  /** link back up to git task */
  GitTask gitTask;

  /**
   * file of the git file in local cloned repo
   */
  File gitFile;
  
  /** if this will be an insert or update */
  def existsInGit;
  
  /** if this is a file or directory */
  def isDirectory;

  /** path in the repo, e.g. /some/path/file.txt */
  String repoPath;
  
  /** call this after constructing to figure out the path and if directory etc */
  def init() {
    
    //DEBUG: repoParentDir is /opt/appserv/local/git.groovy/data/mchyzerRepo/mchyzer
    //DEBUG: repo url is ssh://mchyzer@serpotsl01.seo.int/home/mchyzer/git
    //DEBUG: repo has already been cloned, not pulled due to --pull=false
    //DEBUG: addFilesToMap(/opt/appserv/local/git.groovy/data/mchyzerRepo/mchyzer/git/testGit/src/Test.java, /testGit/src/Test.java)
    
    this.gitTask.gitConfig.repoDir();
    
    this.gitTask.gitConfig.inputName;

    if (!this.gitFile.getAbsolutePath().startsWith(this.gitTask.gitConfig.repoDir().getAbsolutePath())) {
      throw new RuntimeException("why does gitFile not start with repoDir: " + this.gitFile.getAbsolutePath()
        + ", " + this.gitTask.gitConfig.repoDir().getAbsolutePath());
    }

    this.repoPath = this.gitFile.getAbsolutePath().substring(this.gitTask.gitConfig.repoDir().getAbsolutePath().length(), 
      this.gitFile.getAbsolutePath().length());

    if (!this.repoPath.contains("/")) {
      //relative file
      this.normalOsPath = new File(this.repoPath).getAbsolutePath();
    } else if (this.repoPath == this.gitTask.gitConfig.inputName) {
      //one file inputted
      
      this.normalOsPath = new File(this.repoPath.substring(this.repoPath.lastIndexOf('/') + 1, this.repoPath.length())).getAbsolutePath();
    } else {
      //it was a dir, and this is a sub file of the dir, add one for slash
      this.normalOsPath = new File(this.repoPath.substring(this.gitTask.gitConfig.inputName.length() + 1, this.repoPath.length()));
    
    }
        
    if (new File(normalOsPath).isDirectory()) {
      this.isDirectory = true;
    } else if (new File(this.normalOsPath).isDirectory()) {
      this.isDirectory = true;
    } else {
      this.isDirectory = false;
    }
    this.gitTask.gitConfig.logDebug("fileObject.init() normalOsPath: " + this.normalOsPath
      + ", isDirectory: " + this.isDirectory + ", repoPath: " + this.repoPath + ", gitFile: , " + this.gitFile.getAbsolutePath());
  }

  /**
   * keep in metadata the local path and repo and path
   * @return void
   */
  def syncUpFileAssociationWithMetadata() {
    //lets register this linking in the state file if it is not already there
    
    //      returns repoName__fullyQualifiedFileName
    //parse it again so we dont step on toes
    ConfigObject metadataConfigObject = this.gitTask.gitConfig.metadataStateConfigObject();
    String gitRepoAndFile =
          metadataConfigObject.localFileToRepoFile.get(this.normalOsPath);

    String expected = this.gitTask.gitConfig.repo + "__" + this.repoPath;
    
    if (expected != gitRepoAndFile) {
      this.gitTask.gitConfig.logDebug("Updating metadata: " + this.normalOsPath + " --> " + expected);
      metadataConfigObject.localFileToRepoFile.put(this.normalOsPath, expected);
      
      this.gitTask.gitConfig.metadataStateFile.withWriter { writer ->
         metadataConfigObject.writeTo(writer);
      }
    }
  }
  
  /** file name, e.g. temp.txt */
  def name() {
    return gitFile().name;
  }
  
  /** path on this server absolute parent from git,
   * e.g. /tmp/gitgroovy/20080904_11_34_51_709_2430/checkout/sysadmin/servers/cluster/lukes/tmp */
  def localGitParentPath() {
    return this.gitFile.getParent();
  }

  /** e.g. /tmp/gitgroovy/20080904_11_34_51_709_2430/backup/etc/temp.txt */
  String backupFilePath() {
    return this.gitTask.gitConfig.backupDirName + this.normalOsPath;
  }

  /** e.g. /tmp/gitgroovy/20080904_11_34_51_709_2430/backup/etc/temp.txt */
  File backupFile() {
    return new File(this.backupFilePath());
  }

  /** mkdirs for parent file */
  void backupFileParentInit() {
    def theBackupFile = this.backupFile();
    gitTask.mkdirs(theBackupFile.getParentFile());
  }

  /** compare local file on file system with git
   * return true if different, false if not */
  boolean diffLocalWithGit(printResults) {
    def contentDiffers = diffLocalContentWithGit(printResults);
    return contentDiffers;
  }

  boolean diffLocalContentWithGit(printResults) {
    if (this.isDirectory) {
      def localExists = new File(normalOsPath).exists();
      def gitExists = this.gitFile.exists();
      if (localExists == gitExists) {
        return false;
      }
      if (printResults) {
        println("Dir " + this.normalOsPath + " exists locally? " + (localExists ? "T" : "F")
          + " exists in git? " + (gitExists ? "T" : "F"));
      }
      return true;
    }
    return gitTask.diff(normalOsPath, this.gitFile.getAbsolutePath(), printResults);
  }

  /** get the File object of the normal OS file (e.g. /tmp/temp8.txt) */
  def normalOsFile() {
    return new File(this.normalOsPath);
  }

  /** get the local checksum which is the checksum of the local file concatenated with permissions and user/group */
  String checksumLocal() {
    def localChecksum = this.isDirectory ? "" : this.gitTask.checksum(this.normalOsPath);
    return localChecksum;
  }

  String checksumGit() {
    return this.isDirectory ? "" : this.gitTask.checksum(this.localGitFilePath());
  }


}


class GitConfig {
  
  //link back to the task to be able to call methods
  GitTask gitTask;

  //the config file for the git task
  def config = new ConfigSlurper().parse(new File("/opt/appserv/common/binGroovy/gitgroovy.properties").toURI().toURL());

  //optoins passed in to the program (e.g. --debug=true).  key is --debug, value is true
  def options = new HashMap();
  
  //args to program, with options stripped off, e.g. "get"
  List<String> realArgs = new ArrayList();
  
  //if we are in debug mode or not
  def debugMode = false;

  //see if we are doing a file level or directory level operation
  def isFileNotDir;

  boolean compareFiles;
  boolean commitFile;
  String comment;
  boolean getFiles;
  boolean browseFiles;
  boolean historyFile;
  boolean annotateFile;
  def env = System.getenv();
  def tempDirName;

  boolean pull = true;
  
  //repo name in config file
  String repo;
  String repoUrl;
  
  def backupDirName;
        
  /** file types config object */
  def gitgroovyFileTypes;

  /** what the user typed in for command line (after sanitizing with getCanonicalPath() */
  String inputName;

  //read in the data file
  File metadataStateFile = new File('/opt/appserv/common/binGroovy/gitgroovyState.dat');
  
  // object from state file
  ConfigObject metadataStateConfigObject() {
    //metadataStateConfigObject().localFileToRepoFile.get(fileName);
    //returns repoName__fullyQualifiedFileName
    return new ConfigSlurper().parse(this.metadataStateFile.toURI().toURL());

  }
  
  File dataDir = new File("/opt/appserv/local/git.groovy/data");

  File backupDir() {
    gitTask.mkdirs(new File(tempDirName));
    
    this.logDebug("Temp dir: " + tempDirName);

    gitTask.mkdirs(new File(backupDirName));

  }
  
  
  /**
   * e.g. ssh://serpotsl01.seo.int/home/mchyzer/git
   * @return git url
   */
  String gitUrl() {
    return "git+ssh://" + this.repoUrl;
  }
  
  /**
   * e.g. ssh://mchyzer@serpotsl01.seo.int/home/mchyzer/git
   * @return git url
   */
  String repoDirName() {
    int lastSlashIndex = this.repoUrl.lastIndexOf('/');
    if (lastSlashIndex == -1) {
      throw new RuntimeException("No slash in url? " + this.repoUrl);
    }
    return this.repoUrl.substring(lastSlashIndex+1, this.repoUrl.length());
  }

  /**
   * includes the repo userdir, and the repo dir name
   * @return the repo dir
   */
  File repoDir() {
    String dirName = this.repoParentDir().getAbsolutePath() + "/" + this.repoDirName();
    if (dirName.endsWith(".git")) {
      dirName = dirName.substring(0, dirName.length()-4);
    }
    return new File(dirName);
  }

  /**
   * includes the data dir, repo dir, and user dir.  clone goes in here
   * @return repo user dir
   */
  File repoParentDir() {
    return new File("/opt/appserv/local/git.groovy/data/" + this.repo);
  }
    
  /** lazy load the file types db, access with this: for (entry in gitgroovyFileTypes.fileExtensions) */
  def gitgroovyFileTypes() {
    if (gitgroovyFileTypes == null) {
      gitgroovyFileTypes = new ConfigSlurper().parse(new File("/opt/appserv/common/binGroovy/gitgroovyFileTypes.properties").toURI().toURL());
    }
    return gitgroovyFileTypes;
  }

  //put these with highest priority first, name of sub property doesnt really matter, but shouldnt conflict
  //gitdirs {
    //the gitroot of the git server
    //dir1="sysadmin/servers/cluster/lukes"
    //dir2="sysadmin/servers/cluster/testRouter"
  //}
  
  /** if debug mode is true, then print something out */
  def logDebug(message) {
    if (this.debugMode) {
      println("DEBUG: " + message);
    }
  }

  /** add an option: --whatever=val   to a map of options where --whatever is key, and val is value */
  def addOptionToMap(map, option, config) {
    //arg should have an equals sign
    def equalsIndex = option.indexOf("=");
    if (equalsIndex == -1) {
      println("Invalid option: " + option + ", it should look like: --someOption=someValue");
      System.exit(1);
    }
    def key = option.substring(0,equalsIndex);
    def value = option.substring(equalsIndex+1, option.length());
    map.put(key, value);
    if (key != "--debug" && key != "--pull"
        && key != "--repo" ) {
      println("Invalid option: " + key + ", should be either --debug or --repo or --pull");
      System.exit(1);
    }
    //make sure it exists in config
    if (key == "--repo") {
      this.repo = value;
    }
    if (key == "--debug" || key == "--force" || key == "--pull") {
      if (value != "true" && value != "false") {
        println("The value for " + key + " must be either true or false, not '" + value + "'");
        System.exit(1);
      }
    }
  }

  /** process args, pass in this.args */
  def processArgs(args) {

    if (!this.metadataStateFile.exists()) {
      String linkedFile = "/opt/appserv/local/git.groovy/gitgroovyState.dat";
      println("Metadata file does not exist.  Have an admin create one.  e.g. with:\n");
      println("echo 'localFileToRepoFile=[\"\":\"\"]' > " + linkedFile);
      println("chown gituser.git_clone " + linkedFile);
      println("chmod 770 " + linkedFile);
      println("ln -s " + linkedFile + " " + this.metadataStateFile.getAbsolutePath());
      System.exit(1);
    }
        
    for (arg in args) {
      //see if option
      if (arg.startsWith("--")) {
        addOptionToMap(options, arg, this.config);
      } else {
        realArgs.add(arg);
      }
    }

    if (options.get("--debug") == "true") {
      debugMode = true;
    }

    if (options.get("--pull") == "false") {
      pull = false;
    }

    def validCommand = (realArgs.size() >= 1 && realArgs.size() <=3
       && (realArgs[0] == 'get' || realArgs[0] == 'compare' || realArgs[0] == 'browse' || realArgs[0] == 'log' 
         || realArgs[0] == 'annotate' || realArgs[0] == 'blame' || realArgs[0] == 'history' || realArgs[0] == 'commit' ));


    if (validCommand) {
      if (realArgs[0] == 'browse') {
        if (realArgs.size() != 1 && realArgs.size() != 2) {
          validCommand = false;
        }
      } else if (realArgs[0] == 'commit') {
        if (realArgs.size() != 3) {
          validCommand = false;
        }
      } else {
        if (realArgs.size() != 2) {
          validCommand = false;
        }
      }
    }

    if (!validCommand) {
      println("Usage: git.groovy get|commit|compare|history|annotate|browse [absoluteFilename] [comment]\n");
      println("The first argument:\n");
      println("get - get from git and overwrite (and keep backup)");
      println("commit - store a file back to git");
      println("history - see change log");
      println("compare - see difference between git and local, or browser");
      println("browse - view files in repository");
      println("sync - compare files in this directory with the files in the repository");
      println("annotate - show each line of the file and who has edited it when")
      println("");
      println("The 2nd arg is file name, not required for 'browse'.  This file name");
      println("is the path in the repo, of the local path of a file that has been ");
      println("downloaded from the repo with 'get'\n");
      println("The 3rd arg is comment, required for 'commit'");
      println("");
      println("Options:")
      println("--pull=false means dont pull from git");
      println("--debug=true prints debug into about command while executing it");
      System.exit(1);
    }

    compareFiles = realArgs[0] == 'compare';
    getFiles = realArgs[0] == 'get';
    browseFiles = realArgs[0] == 'browse';
    commitFile = realArgs[0] == 'commit';
    historyFile = realArgs[0] == 'history' || realArgs[0] == 'log';
    annotateFile = realArgs[0] == 'annotate' || realArgs[0] == 'blame';
    
    //the file name inputted, massaged
    if (realArgs.size() > 1) {
      inputName = realArgs[1];
    }
    if (realArgs.size() > 2) {
      comment = realArgs[2];
    }

    if (this.browseFiles && (inputName == null || "" == inputName)) {
      inputName = "/"; 
    }
    
    //note, add in commit here?
    if ((!inputName.contains("/") || (!getFiles)) && ( this.repo == null || "" == this.repo)) {
      File inputFile = new File(inputName);
      
      if (inputFile.exists()) {
        
        //      returns repoName__fullyQualifiedFileName
        //parse it again so we dont step on toes
        ConfigObject metadataConfigObject = this.metadataStateConfigObject();
        
        String gitRepoAndFile =
              metadataConfigObject.localFileToRepoFile.get(inputFile.getAbsolutePath());
      
        if (gitRepoAndFile != null) {
          int underscoreIndex = gitRepoAndFile.indexOf("__");
          if (underscoreIndex == -1) {
            throw new RuntimeException("Not expecting this: " + inputFile.getAbsolutePath());
          }
          this.repo = gitRepoAndFile.substring(0, underscoreIndex);
          inputName = gitRepoAndFile.substring(underscoreIndex+2, gitRepoAndFile.length());
        
          this.logDebug("From git metadata state, changing repo to: " + this.repo + ", and file to repo path: " + inputName);
        }
        
      }
    }
    
    if (!inputName.startsWith("/")) {
      println("This file has not been associated with a git repo file (or dont pass in the repo), it must start with /   : " + inputName);
      System.exit(1);
    }
    
    def localFile = new File(inputName);
    def localFileExists = localFile.exists();

    inputName = localFile.getCanonicalPath();
    
    //see if we are doing a file level or directory level operation
    isFileNotDir = !localFile.isDirectory();

    if (this.gitTask.isBlank(this.repo)) {
      println("You need to pass in --repo=something which is defined in "
        + "/opt/appserv/common/binGroovy/gitgroovy.properties, valid repos are:\n");
      for (giturl in this.config.giturls) {
        println("--repo=" + giturl.key);
      }
      System.exit(1);

    }
    
    def foundIt = false;
    for (giturl in config.giturls) {
      // giturl.key is a small friendly name
      // giturl.value is the last part of the url e.g. serpotsl01.seo.int/home/mchyzer/git
      // the url e.g. ssh://serpotsl01.seo.int/home/mchyzer/git
      if (this.repo != null && this.repo == giturl.key) {
        foundIt = true;
        this.repoUrl = giturl.value;
        break;
      }
    }

    if (!foundIt) {
     
      println("Cant find repo '" + this.repo + "' in config file: "
        + "/opt/appserv/common/binGroovy/gitgroovy.properties, valid keys are: ");
      for (giturl in config.giturls) {
        println(giturl.key);
      }
      System.exit(1);
    }
    
    def baseTempDir = "/tmp/gitgroovy";
    if (!new File(baseTempDir).exists()) {
      //we need to create and chmod
      def commands = "/bin/mkdir " + baseTempDir + "\n/bin/chmod 777 " + baseTempDir;
      
      gitTask.run(commands, true, true, true, true);

    }

    //add a rand so we dont have collisions
    DateFormat fileNameFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss_SSS_");

    tempDirName = (baseTempDir + File.separator + fileNameFormat.format(new Date())
      + Math.round(Math.random() * 10000));

    backupDirName = tempDirName + File.separator + "backup";

  }
}

