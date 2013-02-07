package com.mostc.pftt.scenario;

/** scenario for interacting with IMAP mail servers
 * 
 * @author Matt Ficken
 *
 */

public class IMAPScenario extends AbstractNetworkedServiceScenario {

	@Override
	public String getName() {
		return "IMAP";
	}

	@Override
	public boolean isImplemented() {
		return false;
	}

	@Override
	public String getNameWithVersionInfo() {
		return "IMAP"; // XXX -[server implementation and server version]
	}

}
