/*

Background:
Using fingerprinting to dynamically enable caching:
For resources that change occasionally, you can have the browser cache the resource
until it changes on the server, at which point the server tells the browser that a 
new version is available. You accomplish this by embedding a fingerprint of the resource 
in its URL (i.e. the file path). When the resource changes, so does its fingerprint, 
and in turn, so does its URL. As soon as the URL changes, the browser is forced to re-fetch 
the resource. Fingerprinting allows you to set expiry dates long into the future even for 
resources that change more frequently than that. Of course, this technique requires that 
all of the pages that reference the resource know about the fingerprinted URL, which may 
or may not be feasible, depending on how your pages are coded.

What this task does:
 This ant task goes through a fileset, through each line of each file, looking for things like:
 1) Script references
	<script type ="text/javascript" src= "/scripts/script.js"/>
 2) Image references
	<img alt="whatever" src="/images/img.gif"></img>
 3)	Link references
	<link href="style.css"/>
 4)	CSS background images 
	background-image:url(/images/test.jpg);
	
 The task get a checksum value for the file and use it to create a new reference:
 
 By default it create a reference with a the checksum value as a query string:
	/images/test.jpg?5454385464
	/javascript/script.js?4654684864
 
 This solves the caching problem by letting the browser know when a file has changed by changing the url.
 
 Paramenters:
 1) processjs (default value: true)
	It will search for <script type ="text/javascript" src= "/scripts/script.js"/> like references
	
 2) processimg (default value: true)
	It will search for <img alt="whatever" src="/images/img.gif"></img> like references
	
 3) processcss (default value: true)
	It will search for <link href="style.css"/> like references
	
 4) processcss (default value: true)
	It will search for background-image:url(/images/test.jpg); like references
	
 5) uri (default value: false)
	for better caching throght proxies you can use the uri="true" paramenter this way the fingerprint is going to be added
	to the begining of the uri. Something like this:
		/a/5454385464/images/test.jpg
		/a/4654684864/javascript/script.js
		
	Note: this will work only if yo create a rewrite rule on your server. 
		
 6) staticservers
	It prepends to the url a host. It receives a comma separated list of hosts to prepend and it choose one randomly for each referenced file.
	Example:    staticservers="static1.yourhost.com,static2.yourhost.com"
	Return:
		http://static1.yourhost.com/images/test.jpg?5454385464
		http://static2.yourhost.com/javascript/script.js?4654684864
	
 
This code was written by Juan Ignacio Donoso: juan.ignacio@voxound.com & Agustin Feuerhake: agustin@voxound.com
based on other Tasks included in moxiecode js-build-tools, and has an MIT license.
*/

package com.moxiecode.ant.tasks;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;
import static org.apache.tools.ant.Project.MSG_VERBOSE;
import static org.apache.tools.ant.Project.MSG_WARN;
import static org.apache.tools.ant.Project.MSG_INFO;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.Properties;
import java.util.Vector;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.ArrayList;

public class CacheFingerprintTask extends Task {

    private Vector<FileSet> filesets = new Vector<FileSet>();
	protected String buildPath;
	private boolean processJS = true;
	private boolean processIMG = true;
	private boolean processCSS = true;
	private boolean processCSSImages = true;
	private boolean uri = false;
	private String staticServers[] = null;	
	private int stats[] = {0,0,0,0};	
	
	public void setBuildPath(String _buildPath) {
		this.buildPath = _buildPath;
	}
	
	public void setProcessJS(boolean _processJS){
		this.processJS = _processJS;
	}
	
	public void setProcessIMG(boolean _processIMG){
		this.processIMG = _processIMG;
	}

	public void setProcessCSS(boolean _processCSS){
		this.processCSS = _processCSS;
	}
	
	public void setProcessCSSImages(boolean _processCSSImages){
		this.processCSSImages = _processCSSImages;
	}
	
	public void setUri(boolean _uri){
		this.uri = _uri;
	}
	
	public void setStaticServers(String _serverNames){
		this.staticServers = _serverNames.replaceAll("\\s+|http:\\/\\/","").split(",");
	}

    public void addFileset(FileSet fileset) {
        filesets.add(fileset);
    }

    protected void validate() {
        if (filesets.size() < 1)
            throw new BuildException("fileset not set");
    }

    public void execute() {
		File srcFile;
		File destFile;
		BufferedReader srcFileBuffer;
		BufferedWriter destFileBuffer;
		FileInputStream srcInputStream;
		FileOutputStream destOutputStream;
		String line;
        validate();
        for (Iterator itFSets = filesets.iterator(); itFSets.hasNext();) {
            FileSet fs = (FileSet) itFSets.next();
            DirectoryScanner ds = fs.getDirectoryScanner(getProject());
            String[] includedFiles = ds.getIncludedFiles();			
            for (int i = 0; i < includedFiles.length; i++) {
                try {
					//Open src file
					ArrayList<String> tempFile = new ArrayList<String>(200);
					String srcFilename = includedFiles[i].replace('\\', '/');
                    log("Scanning " + srcFilename, MSG_INFO);
					srcFilename = srcFilename.substring(srcFilename.lastIndexOf("/") + 1);
					srcFile = new File(ds.getBasedir(), includedFiles[i]);
					srcInputStream = new FileInputStream(srcFile);
					srcFileBuffer = new BufferedReader(new InputStreamReader(srcInputStream));
					
					Pattern jsPattern = Pattern.compile("^\\s*<(?i:script)\\s+.*(?i:src)\\s*=\\s*\"([^\"]*)\".*$");
					Pattern imgPattern = Pattern.compile("^\\s*<(?i:img)\\s+.*(?i:src)\\s*=\\s*\"([^\"]*)\".*$");
					Pattern cssPattern = Pattern.compile("^\\s*<(?i:link)\\s+.*(?i:href)\\s*=\\s*\"([^\"]*)\".*$");
					Pattern cssImagesPattern = Pattern.compile("^.*background(?:-image)?[ ]*:.*url\\(['\"]?([^'\"]*)['\"]?\\);?.*$");
					
					// Read src file (by lines)
					while ((line = srcFileBuffer.readLine()) != null) {
						if(processJS) line = addFingerprint(jsPattern, line, true);
						if(processIMG) line = addFingerprint(imgPattern, line, true);
						if(processCSS) line = addFingerprint(cssPattern, line, true);
						if(processCSSImages) line = addFingerprint(cssImagesPattern, line, true);
						tempFile.add(line);
					}	
					// Close src file
					srcFileBuffer.close();
					
					// Open destination file
					destFile = new File(ds.getBasedir(), includedFiles[i]);
					destOutputStream = new FileOutputStream(destFile);
					destFileBuffer = new BufferedWriter(new OutputStreamWriter(destOutputStream));
					
					// Write destination file
					for(int j=0;j<tempFile.size();j++){
						destFileBuffer.write((String)tempFile.get(j));
						destFileBuffer.newLine();
					}
					
					// Close destination file
					destFileBuffer.close();
					
				} catch (Exception e) {
                    e.printStackTrace();
                }
				stats[3]++;
            }
        }
		
		// Show some stats
		log("FINISHED: Fingerprint: " + stats[0] + ", External URLs: " + stats[1] + ", Not found: " + stats[2] + ", TOTAL FILES SCANNED: " + stats[3], MSG_INFO);

    }
	
	private String addFingerprint(Pattern _pattern, String _line, boolean _addServer )  {	
		Matcher matcher = _pattern.matcher(_line);
		if (matcher.matches()) {
			MatchResult res = matcher.toMatchResult();
			// Check whether the reference is a url
			Pattern urlPat = Pattern.compile("https?:\\/\\/([-\\w\\.]+)+(:\\d+)?(\\/([\\w\\/_\\.]*(\\?\\S+)?)?)?");
			Matcher ulrmatcher = urlPat.matcher(res.group(1));
			if(!ulrmatcher.find()){
				String refFilename = buildPath + res.group(1);
				File refFile = new File(refFilename);
				if(refFile.exists()){
					String checksum = getChecksum(refFile);
					String withFingerprint;
					if(uri)
						withFingerprint = "/a/" + checksum + res.group(1);
					else
						withFingerprint =  res.group(1)+"?" + checksum;
					if(_addServer) withFingerprint = addServer(withFingerprint, Long.parseLong(checksum));
					_line = _line.replaceFirst(res.group(1), withFingerprint);
					stats[0]++;
					log("  Added fingerprint " + withFingerprint, MSG_INFO);
				}
				else {
					stats[2]++;
					log("  Referenced file not found " + refFilename, MSG_WARN);
				}
			}
			else {
				stats[1]++;
				log("  Referenced is a url " + res.group(1), MSG_INFO);
			}
		}
		return _line;
	}
	
	private String addServer(String _path, long _checksum)  {
		if(staticServers != null){
			String currentServer = staticServers[(int)(_checksum%(long)staticServers.length)];
			_path = "http://" + currentServer + _path;
		}
		return _path;
	}
	
	private String getChecksum(File _srcFile){
		try {
			// Calculate the CRC-32 checksum of this file
			CheckedInputStream cis = new CheckedInputStream(new FileInputStream(_srcFile), new CRC32());
			byte[] tempBuf = new byte[128];
			while (cis.read(tempBuf) >= 0) {
			}
			Long checksum = cis.getChecksum().getValue();
			return checksum.toString();
		}
		catch (IOException e) {
			e.printStackTrace();
			return "";
		}
	}
}
