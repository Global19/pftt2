package com.mostc.pftt.scenario;

import com.github.mattficken.io.StringUtil;
import com.mostc.pftt.host.AHost;
import com.mostc.pftt.host.Host;
import com.mostc.pftt.host.RemoteHost;
import com.mostc.pftt.model.phpt.PhpBuild;
import com.mostc.pftt.results.ConsoleManager;
import com.mostc.pftt.results.ConsoleManager.EPrintType;

/** Scenarios that test PHP using builds and test packs that are stored remotely and accessed using SMB.
 *
 * This testing is important even on recent PHP versions, as proven by php bug #63241.
 * 
 * @author Matt Ficken
 * 
 */

public abstract class AbstractSMBScenario extends AbstractRemoteFileSystemScenario {
	protected final RemoteHost remote_host;
	protected final String base_file_path, base_share_name;
	
	public AbstractSMBScenario(RemoteHost remote_host, String base_file_path, String base_share_name) {
		this.remote_host = remote_host;
		//
		if (StringUtil.isEmpty(base_file_path))
			// fallback to a default path, @see SMBDeduplicationScenario
			base_file_path = remote_host.isWindows() ? "C:\\PFTT-Share" : "/var/data/PFTT-Share";
		else if (StringUtil.isEmpty(AHost.basename(base_file_path)))
			// base_file_path ~= C:\
			base_file_path += "\\PFTT-Share";
		if (StringUtil.isNotEmpty(base_share_name))
			base_share_name = base_share_name.trim();
		if (StringUtil.isEmpty(base_share_name)) {
			base_share_name = AHost.basename(base_file_path);
			if (StringUtil.isEmpty(base_share_name))
				base_share_name = "\\PFTT-Share";
		}
		//
		this.base_file_path = base_file_path;
		this.base_share_name = base_share_name;
	}
	
	@Override
	public boolean allowPhptInPlace() {
		// always make sure test-pack is installed onto SMB Share
		// otherwise, there wouldn't be a point in testing on SMB
		return false;
	}
	
	/** creates a File Share and connects to it.
	 * 
	 * a test-pack can then be installed on that File Share.
	 * 
	 * @param cm
	 * @param host
	 * @return TRUE on success, FALSE on failure (can't use this storage if failure)
	 */
	@Override
	public SMBStorageDir createStorageDir(ConsoleManager cm, AHost host) {
		SMBStorageDir dir = newSMBStorageDir();
		
		if ( createShare(dir, cm) ) {
			if ( connect(dir, cm, host) ) {
				return dir;
			}
		}
		
		// failed, try cleaning up
		dir.delete(cm, host);
		
		return null;
	}
	
	protected SMBStorageDir newSMBStorageDir() {
		return new SMBStorageDir();
	}
	
	public class SMBStorageDir implements ITestPackStorageDir {
		// file path is path on server where share is stored
		// network path is in both UNC and URL format (UNC for Windows, URL for Linux)
		protected String share_name, remote_path, unc_path, url_path, local_path;
		
		@Override
		public boolean notifyTestPackInstalled(ConsoleManager cm, AHost local_host) {
			return true;
		}
		
		@Override
		public boolean delete(ConsoleManager cm, AHost local_host) {
			return disconnect(this, cm, local_host) && deleteShare(this, cm, local_host);
		}

		@Override
		public String getLocalPath(AHost local_host) {
			return local_path; // H: I: J: ... Y:
		}
		
	} // end public class SMBStorageDir
	
	@Override
	public boolean setup(ConsoleManager cm, Host host, PhpBuild build, ScenarioSet scenario_set) {
		return createShare(newSMBStorageDir(), cm);
	}
	
	public boolean shareExists(ConsoleManager cm, String share_name) {
		if (!remote_host.isWindows())
			return false; // XXX samba support
		
		try {
			String output_str = remote_host.execElevatedOut("NET SHARE", AHost.ONE_MINUTE).output;
			
			return output_str.contains(share_name);
		} catch ( Exception ex ) {
			cm.addGlobalException(EPrintType.CANT_CONTINUE, "shareExists", ex, "can't tell if share exists");
		}
		return false;
	}
	
	protected void makeShareName(SMBStorageDir dir, ConsoleManager cm) {
		// make a unique name for the share
		for ( int i=1 ; i < 65535 ; i++ ) {
			dir.remote_path = base_file_path + "-" + i;
			dir.share_name = base_share_name + "-" + i;
			if (!remote_host.exists(dir.remote_path)) {
				// share may still exist, but at a different remote file path (double check to avoid `net share` failure)
				if (!shareExists(cm, dir.share_name)) {
					break;
				}
			}
		}
		
		dir.unc_path = "\\\\"+remote_host.getHostname()+"\\"+dir.share_name; // for Windows
		dir.url_path = "smb://"+remote_host.getHostname()+"/"+dir.share_name; // for linux
	} // end protected void makeShareName
	
	protected boolean createShare(SMBStorageDir dir, ConsoleManager cm) {
		makeShareName(dir, cm);
		
		cm.println(EPrintType.IN_PROGRESS, getName(), "Selected share_name="+dir.share_name+" remote_path="+dir.remote_path+" (base: "+base_file_path+" "+base_share_name+")");
		
		try {
			if (remote_host.isWindows()) {
				if (!createShareWindows(dir, cm))
					return false;
			} else if (!createShareSamba(dir, cm)) {
				return false;
			}
		} catch (Exception ex ) {
			cm.addGlobalException(EPrintType.OPERATION_FAILED_CONTINUING, getClass(), "createShare", ex, "", remote_host, dir.remote_path, dir.share_name);
			return false;
		}
		
		cm.println(EPrintType.COMPLETED_OPERATION, getName(), "Share created: unc="+dir.unc_path+" remote_file="+dir.remote_path+" url="+dir.url_path);
		
		return true;
	} // end protected boolean createShare
	
	protected boolean createShareWindows(SMBStorageDir dir, ConsoleManager cm) throws Exception {
		return doCreateShareWindows(cm, dir.remote_path, dir.share_name);
	}
	
	protected boolean doCreateShareWindows(ConsoleManager cm, String remote_path, String share_name) throws Exception {
		remote_path = remote_path.replace("C", "G"); // TODO
		
		remote_host.mkdirs(remote_path);
		
		String cmd = "NET SHARE "+share_name+"="+remote_path+" /Grant:"+remote_host.getUsername()+",Full";
		System.out.println("cmd "+cmd);
		return remote_host.execElevated(cm, getClass(), cmd, AHost.FOUR_HOURS);
	}
	
	protected boolean createShareSamba(SMBStorageDir dir, ConsoleManager cm) {
		// XXX
		return false;
	}
	
	protected boolean connect(SMBStorageDir dir, ConsoleManager cm, AHost local_host) {
		if (remote_host.isRemote()) {
			try {
				if (remote_host.isWindows())
					return connectFromWindows(dir, cm, local_host);
				else
					return connectFromSamba(dir, cm);
			} catch ( Exception ex ) {
				cm.addGlobalException(EPrintType.OPERATION_FAILED_CONTINUING, getClass(), "connect", ex, "", remote_host, local_host);
				return false;
			}
		} else {
			// host is local, try using a local drive, normal file system operations, not SMB, etc...
			dir.local_path = dir.remote_path;
			
			return true;
		}
	} // end protected boolean connect
	
	protected static final String[] DRIVES = new String[]{"H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y"}; // 18
	protected boolean connectFromWindows(SMBStorageDir dir, ConsoleManager cm, AHost local_host) throws Exception {
		dir.local_path = null;
		for ( int i=0 ; i < DRIVES.length ; i++ ) {
			if (!local_host.exists(DRIVES[i] + ":\\")) {
				dir.local_path = DRIVES[i] + ":";
				break;
			}
		}
		if (dir.local_path==null)
			return false;
		
		String cmd = "NET USE "+dir.local_path+" "+dir.unc_path+" /user:"+remote_host.getUsername()+" "+remote_host.getPassword();
		
		return local_host.execElevated(cm, getClass(), cmd, AHost.ONE_MINUTE);
	}
	
	protected boolean connectFromSamba(SMBStorageDir dir, ConsoleManager cm) {
		// XXX
		return false;
	}
	
	protected boolean deleteShare(SMBStorageDir dir, ConsoleManager cm, AHost host) {
		if (doDeleteShareWindows(cm, dir.remote_path)) {
			cm.println(EPrintType.IN_PROGRESS, getClass(), "Share deleted: remote_file="+dir.remote_path+" unc="+dir.unc_path+" url="+dir.url_path);
			
			return true;
		} else {
			cm.println(EPrintType.CANT_CONTINUE, getClass(), "Unable to delete share: remote_file="+dir.remote_path+" unc="+dir.unc_path);
			
			return false;
		}
	}
	
	protected boolean doDeleteShareWindows(ConsoleManager cm, String remote_path) {
		try {
			if (remote_host.execElevated(cm, getClass(), "NET SHARE "+remote_path+" /DELETE", AHost.ONE_MINUTE)) {
				try {
					remote_host.delete(remote_path);
					
					return true;
				} catch ( Exception ex ) {
					throw ex;
				}
			}
		} catch ( Exception ex ) {
			cm.addGlobalException(EPrintType.OPERATION_FAILED_CONTINUING, getClass(), "deleteShare", ex, "", remote_host, remote_path);
		}
		return false;
	}
	
	protected boolean disconnect(SMBStorageDir dir, ConsoleManager cm, AHost host) {
		try {
			if (host.exec(cm, getClass(), "NET USE "+dir.local_path+" /DELETE", AHost.ONE_MINUTE)) {
				cm.println(EPrintType.IN_PROGRESS, getClass(), "Disconnected share: local="+dir.local_path);
			}
		} catch ( Exception ex ) {
			cm.addGlobalException(EPrintType.OPERATION_FAILED_CONTINUING, getClass(), "disconnect", ex, "Unable to disconnect: local="+dir.local_path, host, dir.local_path);
		}
		return false;
	}
	
} // end public abstract class AbstractSMBScenario
