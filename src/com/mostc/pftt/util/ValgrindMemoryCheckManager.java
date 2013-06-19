package com.mostc.pftt.util;

import com.mostc.pftt.host.AHost;
import com.mostc.pftt.host.AHost.ExecHandle;
import com.mostc.pftt.model.core.PhpBuild;
import com.mostc.pftt.results.ConsoleManager;
import com.mostc.pftt.results.EPrintType;
import com.mostc.pftt.scenario.ScenarioSet;

/** Runs Valgrind's Memcheck tool.
 * 
 * This tracks validity and addressibility of a program's memory.
 * 
 * Runs on Linux and Mac OS X (there is an unofficial port to FreeBSD and an experimental port to Windows).
 * 
 *
 */

public class ValgrindMemoryCheckManager extends DebuggerManager {
	protected AHost valgrind_host;
	protected boolean has_valgrind;
	
	protected boolean ensureValgrind(ConsoleManager cm, AHost host, PhpBuild build) {
		if (this.valgrind_host==host)
			return has_valgrind;
		this.valgrind_host = host;
		
		if (!(has_valgrind = host.hasCmd("valgrind"))) {
			cm.println(EPrintType.CLUE, getClass(), "valgrind was not found. You must install valgrind and it must be on your $PATH");
		}
		return has_valgrind;
	}
	
	@Override
	public ValgrindDebugger newDebugger(ConsoleManager cm, AHost host,
			ScenarioSet scenario_set, Object server_name, PhpBuild build,
			int process_id, ExecHandle process) {
		if (!ensureValgrind(cm, host, build))
			return null; // valgrind not installed
		
		// TODO Auto-generated method stub
		return null;
	}
	
	public class ValgrindDebugger extends Debugger {
		protected AHost.ExecHandle process;
		
		public ValgrindDebugger(AHost host, String command_line) throws Exception {
			process = host.execThread("valgrind --leak-check=yes "+command_line);
		}

		@Override
		public void close(ConsoleManager cm) {
			process.close(cm, true);
		}

		@Override
		public boolean isRunning() {
			return process.isRunning();
		}
		
	}

} // end public class ValgrindMemoryCheckManager