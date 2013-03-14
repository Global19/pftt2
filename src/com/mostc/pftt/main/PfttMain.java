package com.mostc.pftt.main;

import groovy.lang.Binding;
import groovy.ui.Console;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.codehaus.groovy.tools.shell.Groovysh;
import org.codehaus.groovy.tools.shell.IO;

import com.github.mattficken.io.StringUtil;
import com.mostc.pftt.host.AHost;
import com.mostc.pftt.host.LocalHost;
import com.mostc.pftt.model.app.PhpUnitSourceTestPack;
import com.mostc.pftt.model.core.EBuildBranch;
import com.mostc.pftt.model.core.EBuildType;
import com.mostc.pftt.model.core.PhpBuild;
import com.mostc.pftt.model.core.PhpDebugPack;
import com.mostc.pftt.model.core.PhptSourceTestPack;
import com.mostc.pftt.model.core.PhptTestCase;
import com.mostc.pftt.model.smoke.ESmokeTestStatus;
import com.mostc.pftt.model.smoke.PhptTestCountsMatchSmokeTest;
import com.mostc.pftt.model.smoke.RequiredExtensionsSmokeTest;
import com.mostc.pftt.model.smoke.RequiredFeaturesSmokeTest;
import com.mostc.pftt.results.AbstractPhpUnitRW;
import com.mostc.pftt.results.AbstractPhptRW;
import com.mostc.pftt.results.AbstractReportGen;
import com.mostc.pftt.results.ConsoleManager;
import com.mostc.pftt.results.LocalConsoleManager;
import com.mostc.pftt.results.PHPTReportGen;
import com.mostc.pftt.results.PhpResultPack;
import com.mostc.pftt.results.PhpResultPackReader;
import com.mostc.pftt.results.PhpResultPackWriter;
import com.mostc.pftt.results.PhpUnitReportGen;
import com.mostc.pftt.results.ConsoleManager.EPrintType;
import com.mostc.pftt.runner.LocalPhpUnitTestPackRunner;
import com.mostc.pftt.runner.LocalPhptTestPackRunner;
import com.mostc.pftt.scenario.AbstractSAPIScenario;
import com.mostc.pftt.scenario.EScenarioSetPermutationLayer;
import com.mostc.pftt.scenario.Scenario;
import com.mostc.pftt.scenario.ScenarioSet;
import com.mostc.pftt.util.DownloadUtil;
import com.mostc.pftt.util.HostEnvUtil;
import com.mostc.pftt.util.WinDebugManager;
import com.mostc.pftt.util.WindowsSnapshotDownloadUtil;
import com.mostc.pftt.util.WindowsSnapshotDownloadUtil.FindBuildTestPackPair;

/** main class for PFTT
 * 
 * launches PFTT and loads any other classes, etc... needed to execute commands given to PFTT.
 * 
 * @author Matt Ficken
 * 
 * To Learn about the details of PHP Testing, PHPT, PhpUnit, Debugging or Configuration, see these classes:
 * -ApacheManager IISManager BuiltinWebServerManager
 * -PhptTestCase AbstractPhptTestCaseRunner2
 * -PhpBuild
 * -PhpUnitTestCase AbstractPhpUnitTestCaseRunner
 * 
 */

//
// TODO script/java app to
//   1. rgn 5.5
//   2. core_all
//   3. app_all symfony
//   4. cmp-report
//   5. email
//   6. zip
// 
// TODO try to unmount network drives on shutdown
// TODO UI testing
// TODO installation ... single .zip file to download with install wizard/script
//         http://izpack.org/   include Java JDK
//
// TODO pftt explain
//        -shows PhpIni, etc.. not as a separate file though
//           -if you need it for debug, use it from explain
//                -ie force people to do it at least partially the efficient PFTT way
//           -if you need it to setup, use setup cmd
public class PfttMain {
	protected LocalHost host;
	
	public PfttMain() {
		host = new LocalHost();
	}
		
	@SuppressWarnings("unused")
	protected File telem_dir() {
		File file;
		if (AHost.DEV > 0) {
			file = new File(host.getPhpSdkDir(), "Dev-"+AHost.DEV);
		} else {
			file = new File(host.getPhpSdkDir());
		}
		file.mkdirs();
		return file;
	}
	
	protected PhpResultPackReader last_telem(ConsoleManager cm, PhpResultPackWriter not) throws FileNotFoundException {
		File[] files = telem_dir().listFiles();
		File last_file = null;
		for (File file : files) {
			if (PhpResultPackReader.isResultPack(file)) {
				if (not!=null && file.equals(not.getTelemetryDir()))
					// be sure to not find the telemetry that is being written presently
					continue;
				if (last_file==null || last_file.lastModified() < file.lastModified())
					last_file = file;
			}
		}
		return last_file == null ? null : PhpResultPackReader.open(cm, host, last_file);
	}

	public void run_all(LocalConsoleManager cm, PhpBuild build, PhptSourceTestPack test_pack, AHost storage_host, List<ScenarioSet> scenario_sets) throws Exception {
		PhpResultPackWriter tmgr = new PhpResultPackWriter(host, cm, telem_dir(), build, test_pack);
		cm.println(EPrintType.CLUE, getClass(), "Writing Result-Pack: "+tmgr.getTelemetryDir());
		for ( ScenarioSet scenario_set : scenario_sets ) {
			/* TODO for ( Host host : config.getHosts() ) {
				if (host.isRemote()) {
					install_build();
				}*/
				//
			if (!cm.isSkipSmokeTests()) {
				{
					// TODO test running PHPTs on a build that is missing a DLL that is
					RequiredExtensionsSmokeTest test = new RequiredExtensionsSmokeTest();
					//
					// on Windows, missing .DLLs from a php build will cause a blocking winpop dialog msg to appear
					// in such a case, the test will timeout after 1 minute and then fail (stopping at that point is important)
					// @see PhpBuild#getExtensionList
					if (test.test(build, cm, host, AbstractSAPIScenario.getSAPIScenario(scenario_set).getSAPIType())==ESmokeTestStatus.FAIL) {
						// if this test fails, RequiredFeaturesSmokeTest will fail for sure
						cm.println(EPrintType.CANT_CONTINUE, "Main", "Failed smoke test: "+test.getName());
						break;
					}
				}
				{
					RequiredFeaturesSmokeTest test = new RequiredFeaturesSmokeTest();
					if (test.test(build, cm, host)==ESmokeTestStatus.FAIL) {
						cm.println(EPrintType.CANT_CONTINUE, "Main", "Failed smoke test: "+test.getName());
						break;
					}
				}
			}
			//
			
			LocalPhptTestPackRunner test_pack_runner = new LocalPhptTestPackRunner(tmgr.getConsoleManager(), tmgr, scenario_set, build, storage_host, host);
			cm.showGUI(test_pack_runner);
			
			test_pack_runner.runAllTests(test_pack);
			
			// TODO archive telemetry into 7zip file
			// TODO upload (if in config file)
			AbstractPhptRW writer = tmgr.getPHPT(host, scenario_set);
			if (writer==null)
				continue;
			writer.close();
			/*tmgr.close();
			
			//
			{
				PhptTestCountsMatchSmokeTest test = new PhptTestCountsMatchSmokeTest();
				if (test.test(tmgr)==ESmokeTestStatus.FAIL) {
					cm.println(EPrintType.CANT_CONTINUE, "Main", "Failed smoke test: "+test.getName());
				}
			}
			//
			
			// TODO email report (if in config file)
			phpt_report(cm, tmgr);*/
		}
		tmgr.close();
	} // end public void run_all
	
	protected void phpt_report(ConsoleManager cm, PhpResultPackWriter test_telem) throws FileNotFoundException {	
		PhpResultPackWriter base_telem = test_telem; // TODO temp last_telem(test_telem);
		if (base_telem==null) {
			// this isn't an error, so don't interrupt the test run or anything
			System.err.println("User Info: run again (with different build and/or different test-pack) and PFTT");
			System.err.println("                  will generate an FBC report comparing the builds");
			System.err.println();
		} else {
			// TODO temp 
			//show_report(cm, new FBCReportGen2(base_telem.g));
		}
	}
	
	protected void show_report(ConsoleManager cm, AbstractReportGen report) {
		String html_file = report.createHTMLTempFile(host);
		System.out.println(html_file);
		try {
			Desktop.getDesktop().browse(new File(html_file).toURI());
		} catch ( Exception ex ) {
			cm.addGlobalException(EPrintType.SKIP_OPTIONAL, getClass(), "show_report", ex, "unable to show HTML file: "+html_file);
		}
	}

	public void run_named_tests(LocalConsoleManager cm, PhpBuild build, PhptSourceTestPack test_pack, AHost storage_host, List<ScenarioSet> scenario_sets, List<String> names) throws Exception {
		for ( ScenarioSet scenario_set : scenario_sets ) {
			//
			if (!cm.isSkipSmokeTests()) {
				{
					RequiredExtensionsSmokeTest test = new RequiredExtensionsSmokeTest();
					if (test.test(build, cm, host, AbstractSAPIScenario.getSAPIScenario(scenario_set).getSAPIType())==ESmokeTestStatus.FAIL) {
						cm.println(EPrintType.CANT_CONTINUE, "Main", "Failed smoke test: "+test.getName());
						break;
					}
				}
				{
					RequiredFeaturesSmokeTest test = new RequiredFeaturesSmokeTest();
					if (test.test(build, cm, host)==ESmokeTestStatus.FAIL) {
						cm.println(EPrintType.CANT_CONTINUE, "Main", "Failed smoke test: "+test.getName());
						break;
					}
				}
			}
			//
			
			LinkedList<PhptTestCase> test_cases = new LinkedList<PhptTestCase>();
			
			PhpResultPackWriter tmgr = new PhpResultPackWriter(host, cm, telem_dir(), build, test_pack);
			cm.println(EPrintType.CLUE, getClass(), "Writing Result-Pack: "+tmgr.getTelemetryDir());
			test_pack.cleanup(cm);
			cm.println(EPrintType.IN_PROGRESS, "PhptSourceTestPack", "enumerating test cases from test-pack...");
			test_pack.read(test_cases, names, tmgr.getConsoleManager(), tmgr, build, true); // TODO true?
			cm.println(EPrintType.IN_PROGRESS, "PhptSourceTestPack", "enumerated test cases.");
			
			LocalPhptTestPackRunner test_pack_runner = new LocalPhptTestPackRunner(tmgr.getConsoleManager(), tmgr, scenario_set, build, storage_host, host);
			cm.showGUI(test_pack_runner);
			
			test_pack_runner.runTestList(test_pack, test_cases);
			
			tmgr.close();
			
			//
			{
				PhptTestCountsMatchSmokeTest test = new PhptTestCountsMatchSmokeTest();
				if (test.test(tmgr)==ESmokeTestStatus.FAIL) {
					cm.println(EPrintType.CANT_CONTINUE, "Main", "Failed smoke test: "+test.getName());
				}
			}
			//
			
			phpt_report(cm, tmgr);
		} // end for
	}
	
	/* -------------------------------------------------- */
	
	protected static void cmd_report_gen() {
		// TODO - generate report without running tests, generate from result-packs
	}
	
	protected static void cmd_help() {
		System.out.println("Usage: pftt <optional options> <command>");
		System.out.println();
		System.out.println("Commands:");
		System.out.println("core_all <build> <test-pack> - runs all tests in given test pack");
		System.out.println("core_named <build> <test-pack> <test1> <test2> <test name fragment> - runs named tests or tests matching name pattern");
		System.out.println("core_list <build> <test-pack> <file> - runs list of tests stored in file");
		System.out.println("app_all <build> - runs all application tests specified in a Scenario config file against build");
		System.out.println("app_named <build> <test name fragment> - runs named application tests (tests specified in a Scenario config file)");
		System.out.println("app_list <build> <file> - runs application tests from list in file (tests specified in a Scenario config file)");
		System.out.println("custom <build> - runs PFTT specific functional tests (bugs that can not be tested using PHP testsT)");
		System.out.println("help");
		System.out.println("perf <build> - performance test of build");
		System.out.println("smoke <build> - smoke test a build");
		System.out.println("ui - automated UI (\"app compat\") testing");
		System.out.println("release_get <branch> <build-type> <revision> - download a build and test-pack snapshot release");
		System.out.println("release_get <build|test-pack URL> - download a build or test-pack from any URL");
		System.out.println("release_list <optional branch> <optional build-type> - list snapshot build and test-pack releases");
		System.out.println("telemetry-pkg - package telemetry into single archive file");
		System.out.println("shell - interactive execution of custom instructions");
		System.out.println("shell-ui - gui shell");
		System.out.println("exec <file> - executes shell script (see shell)");
		System.out.println("list - list all scenarios, default builtin and from configuration file(s)");
		System.out.println("upgrade - upgrades PFTT to the latest version");
		System.out.println("setup <build> - sets up scenarios from -config -- installs IIS or Apache to run PHP, etc...");
		System.out.println();
		System.out.println("Options:");
		System.out.println("-gui - show gui for certain commands");
		System.out.println("-config <file1,file2> - load 1+ configuration file(s)");
		System.out.println("-overwrite - overwrites files without prompting (confirmation prompt by default)");
		System.out.println("-src_pack <path> - folder with the source code");
		System.out.println("-debug_pack <path> - folder with debugger symbols (usually folder with .pdb files)");
		System.out.println("-pause - after everything is done, PFTT will wait for user to press any key");
		System.out.println("-randomize_order <0+> - randomizes test case run order");
		System.out.println("-results_only - displays only test results and no other information (for automation).");
		System.out.println("-pftt-debug - shows additional information to help debug problems with PFTT itself");
		System.out.println("-disable_debug_prompt - disables asking you if you want to debug PHP crashes (for automation. default=enabled) (alias: -debug_none)");
		System.out.println("-phpt-not-in-place - copies PHPTs to a temporary dir and runs PHPTs from there (default=disabled, test in-place)");
		System.out.println("-dont-cleanup-test-pack - doesn't delete temp dir created by -phpt-not-in-place or SMB scenario (default=delete)");
		System.out.println("-auto - changes default options for automated testing (-uac -disable_debug_prompt -phpt-not-in-place)");
		if (LocalHost.isLocalhostWindows()) {
			System.out.println("-uac - runs PFTT in Elevated Privileges so you only get 1 UAC popup dialog (when PFTT is started)");
		}
		System.out.println("-run_test_times_all <N> - runs each test N times in a row/consecutively");
		System.out.println("-run_test_times_list <N> <list file> - runs tests in that list N times. if used with -run_test_times_all, tests not in list can be run different number of times from tests in list (ex: run listed tests 5 times, run all other tests 2 times).");
		System.out.println("-debug_all - runs all tests in Debugger");
		System.out.println("-debug_list <list files> - runs tests in list in Debugger (exact name)");
		System.out.println("-skip_list <list files> - skip tests in list (exact name)");
		System.out.println("-run_group_times_all <N> - runs all groups of tests N times (in same order every time, unless -randomize used)");
		System.out.println("-run_group_times_list <N> <list file> - just like run_group_times_all and run_test_times_list (but for groups of tests)");
		System.out.println("-thread_count <N> - sets number of threads to run tests in. running in multiple threads is usually a performance boost. by default, will run with multiple threads and automatically decide the best number of threads to use");
		System.out.println("-max_test_read_count <N> - maximum number of tests to read (without other options, this will be the number of tests run also... tests are normally only run once)");
		System.out.println("-skip_smoke_tests - skips smoke tests and runs tests anyway (BE CAREFUL. RESULTS MAY BE INVALID or INACCURATE)");
		System.out.println("-no_nts - runs tests in any thread, regardless of thread-safety. This can increase load/stress, but may lead to false FAILS/ERRORs, especially in file or database tests.");
		System.out.println();
	} // end protected static void cmd_help
	
	protected static void cmd_smoke() {
		System.err.println("Error: Not implemented");
		new RequiredExtensionsSmokeTest();
		new RequiredFeaturesSmokeTest();
	}
	
	protected static void cmd_shell_ui() {
		System.err.println("Error: Not implemented");
	}
	
	protected static void cmd_exec() {
		System.err.println("Error: Not implemented");
	}
	
	protected static void cmd_shell() {
		IO io = new IO();
		//
		System.setProperty("groovysh.prompt", "hello");
		System.setProperty("jline.terminal", "jline.UnsupportedTerminal"); // WindowsTerminal UnixTerminal
		// Ansi.enabled = false; // true if WindowsTerminal or UnixTerminal?
		
		Binding binding = new Binding();
		//binding.setVariable("client", client)
		
		Groovysh shell = new Groovysh(binding, io);
		
		int code = shell.run();
		
		Console console = new Console();
		//console.setVariable("var1", getValueOfVar1());
		//console.setVariable("var2", getValueOfVar2());
		console.run();
	}
	
	/** single entry point for everything to get ScenarioSets from configuration
	 * or from default if no configuration is given.
	 * 
	 * if configuration is given, the configuration's scenario sets are used entirely in
	 * place of default (not merged with default)
	 * 
	 * @param config
	 * @param layer
	 * @return
	 */
	protected static List<ScenarioSet> getScenarioSets(Config config, EScenarioSetPermutationLayer layer) {
		return config==null?ScenarioSet.getDefaultScenarioSets():config.getScenarioSets(layer);
	}
	
	protected static void cmd_core_all(PfttMain rt, LocalConsoleManager cm, Config config, PhpBuild build, PhptSourceTestPack test_pack) throws Exception {
		List<AHost> hosts = config.getHosts();
		rt.run_all(cm, build, test_pack, hosts.isEmpty() ? new LocalHost() : hosts.get(0), getScenarioSets(config, EScenarioSetPermutationLayer.PHP_CORE));
	}
	
	protected static void cmd_core_list(PfttMain rt, LocalConsoleManager cm, Config config, PhpBuild build, PhptSourceTestPack test_pack, File list_file) throws Exception {
		LinkedList<String> tests = new LinkedList<String>();
		readStringListFromFile(tests, list_file);
		
		List<AHost> hosts = config.getHosts();
		rt.run_named_tests(cm, build, test_pack, hosts.isEmpty() ? new LocalHost() : hosts.get(0), getScenarioSets(config, EScenarioSetPermutationLayer.PHP_CORE), tests);
	}
	
	protected static void cmd_core_named(PfttMain rt, LocalConsoleManager cm, Config config, PhpBuild build, PhptSourceTestPack test_pack, List<String> names) throws Exception {
		List<AHost> hosts = config.getHosts();
		rt.run_named_tests(cm, build, test_pack, hosts.isEmpty() ? new LocalHost() : hosts.get(0), getScenarioSets(config, EScenarioSetPermutationLayer.PHP_CORE), names);
	}

	protected static void cmd_ui() {
		System.err.println("Error: Not implemented");
	}

	protected static void cmd_perf() {
		System.err.println("Error: Not implemented");		
	}
 
	protected static void cmd_release_get(ConsoleManager cm, boolean overwrite, AHost host, URL url) {
		download_release_and_decompress(cm, overwrite, "build", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, url), url);
	}
	
	protected static void cmd_release_get_previous(final ConsoleManager cm, final boolean overwrite, final AHost host, final EBuildBranch branch, final EBuildType build_type) {
		System.out.println("PFTT: release_get: finding previous "+build_type+" build of "+branch+"...");
		final FindBuildTestPackPair find_pair = WindowsSnapshotDownloadUtil.findPreviousPair(build_type, WindowsSnapshotDownloadUtil.getDownloadURL(branch));
		if (find_pair==null) {
			System.err.println("PFTT: release_get: unable to find previous build of "+branch+" of type "+build_type);
			return;
		}
		Thread t0 = new Thread() {
				public void run() {
					download_release_and_decompress(cm, overwrite, "build", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, find_pair.getBuild()), find_pair.getBuild());
				}
			};
		t0.start();
		Thread t1 = new Thread() {
				public void run() {
					download_release_and_decompress(cm, overwrite, "test-pack", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, find_pair.getTest_pack()), find_pair.getTest_pack());
				}
			};
		t1.start();
		Thread t2 = new Thread() {
				public void run() {
					download_release_and_decompress(cm, overwrite, "debug-pack", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, find_pair.getDebug_pack()), find_pair.getDebug_pack());
				}
			};
		t2.start();
		try {
			t0.join();
			t1.join();
			t2.join();
		} catch ( Exception ex ) {
			ex.printStackTrace(); // shouldn't happen
		}
		System.out.println("PFTT: release_get: done.");
	} // end protected static void cmd_release_get_previous
	
	protected static void cmd_release_get_newest(final ConsoleManager cm, final boolean overwrite, final AHost host, final EBuildBranch branch, final EBuildType build_type) {
		System.out.println("PFTT: release_get: finding newest "+build_type+" build of "+branch+"...");
		final FindBuildTestPackPair find_pair = WindowsSnapshotDownloadUtil.findNewestPair(build_type, WindowsSnapshotDownloadUtil.getDownloadURL(branch));
		if (find_pair==null) {
			System.err.println("PFTT: release_get: unable to find newest build of "+branch+" of type "+build_type);
			return;
		}
		Thread t0 = new Thread() {
				public void run() {
					download_release_and_decompress(cm, overwrite, "build", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, find_pair.getBuild()), find_pair.getBuild());
				}
			};
		t0.start();
		Thread t1 = new Thread() {
				public void run() {
					download_release_and_decompress(cm, overwrite, "test-pack", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, find_pair.getTest_pack()), find_pair.getTest_pack());
				}
			};
		t1.start();
		Thread t2 = new Thread() {
				public void run() {
					download_release_and_decompress(cm, overwrite, "debug-pack", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, find_pair.getDebug_pack()), find_pair.getDebug_pack());
				}
			};
		t2.start();
		try {
			t0.join();
			t1.join();
			t2.join();
		} catch ( Exception ex ) {
			ex.printStackTrace();
		}
		System.out.println("PFTT: release_get: done.");
	} // end protected static void cmd_release_get_newest

	protected static void cmd_release_get_revision(final ConsoleManager cm, final boolean overwrite, final AHost host, final EBuildBranch branch, final EBuildType build_type, final String revision) {
		System.out.println("PFTT: release_get: finding "+build_type+" build in "+revision+" of "+branch+"...");
		final FindBuildTestPackPair find_pair = WindowsSnapshotDownloadUtil.getDownloadURL(branch, build_type, revision);
		if (find_pair==null) {
			System.err.println("PFTT: release_get: no build of type "+build_type+" or test-pack found for revision "+revision+" of "+branch);
			return;
		}
		Thread t0 = null, t1 = null, t2 = null;
		if (find_pair.getBuild()!=null) {
			t0 = new Thread() {
					public void run() {
						download_release_and_decompress(cm, overwrite, "build", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, find_pair.getBuild()), find_pair.getBuild());
					}
				};
			t0.start();
		}
		if (find_pair.getTest_pack()!=null) {
			t1 = new Thread() {
					public void run() {
						download_release_and_decompress(cm, overwrite, "test-pack", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, find_pair.getTest_pack()), find_pair.getTest_pack());
					}
				};
			t1.start();
		}
		if (find_pair.getDebug_pack()!=null) {
			t2 = new Thread() {
					public void run() {
						download_release_and_decompress(cm, overwrite, "debug-pack", host, WindowsSnapshotDownloadUtil.snapshotURLtoLocalFile(host, find_pair.getDebug_pack()), find_pair.getDebug_pack());
					}
				};
			t2.start();
		}
		try {
			if (t0!=null)
				t0.join();
			if (t1!=null)
				t1.join();
			if (t2!=null)
				t2.join();
		} catch ( Exception ex ) {
			ex.printStackTrace();
		}
		System.out.println("PFTT: release_get: done.");
	} // end protected static void cmd_release_get_revision
	
	protected static boolean confirm(String msg, boolean def) {
		synchronized(System.in) { // sync in case threaded
			System.out.print("PFTT: "+msg+" [y/N] ");
			BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
			try {
				String line_str = br.readLine();
				if (StringUtil.isEmpty(line_str))
					return def;
				else if (def)
					return StringUtil.startsWithIC(line_str, "n"); // return true unless 'no'
				else
					return StringUtil.startsWithIC(line_str, "y"); // return false unless 'yes'
			} catch ( Exception ex ) {
				return false;
			}
		}
	}
	
	protected static void download_release_and_decompress(ConsoleManager cm, boolean overwrite, String download_type, AHost host, File local_dir, URL url) {
		if (!overwrite && local_dir.exists()) {
			if (!confirm("Overwrite existing folder "+local_dir+"?", true))
				return;
		}
		System.out.println("PFTT: release_get: downloading "+url+"...");
		
		if (DownloadUtil.downloadAndUnzip(cm, host, url, local_dir.getAbsolutePath())) {
			cm.println(EPrintType.COMPLETED_OPERATION, "release_get", download_type+" COPIED TO: "+local_dir);
		} else {
			cm.println(EPrintType.CANT_CONTINUE, "release_get", "unable to decompress "+download_type);
		}
	} // end protected static void download_release_and_decompress

	protected static void cmd_release_list() {
		List<URL> snaps_url;
		for (EBuildBranch branch : EBuildBranch.values()) {
			snaps_url = WindowsSnapshotDownloadUtil.getSnapshotURLSNewestFirst(WindowsSnapshotDownloadUtil.getDownloadURL(branch));
			for (URL snap_url : snaps_url) {
				System.out.print(branch);
				System.out.print(' ');
				System.out.print(WindowsSnapshotDownloadUtil.getRevision(snap_url));
				for (EBuildType build_type:EBuildType.values()) {
					if (!WindowsSnapshotDownloadUtil.hasBuildTypeAndTestPack(snap_url, build_type))
						continue;
					
					System.out.print(' ');
					System.out.print(build_type);
				}
				System.out.println();
			}
		}
	}
	protected static void cmd_release_list(EBuildType build_type, EBuildBranch branch) {
		List<URL> snap_urls = WindowsSnapshotDownloadUtil.getSnapshotURLSNewestFirst(WindowsSnapshotDownloadUtil.getDownloadURL(branch));
		for (URL snap_url:snap_urls) {
			if (WindowsSnapshotDownloadUtil.hasBuildTypeAndTestPack(snap_url, build_type)) {
				System.out.print(branch);
				System.out.print(' ');
				System.out.print(WindowsSnapshotDownloadUtil.getRevision(snap_url));
				System.out.print(' ');
				System.out.println(build_type);
			}
		}
	}
	protected static void cmd_release_list(EBuildBranch branch) {
		List<URL> snaps_url = WindowsSnapshotDownloadUtil.getSnapshotURLSNewestFirst(WindowsSnapshotDownloadUtil.getDownloadURL(branch));
		for (URL snap_url : snaps_url) {
			System.out.print(branch);
			System.out.print(' ');
			System.out.print(WindowsSnapshotDownloadUtil.getRevision(snap_url));
			for (EBuildType build_type:EBuildType.values()) {
				if (!WindowsSnapshotDownloadUtil.hasBuildTypeAndTestPack(snap_url, build_type))
					continue;
				
				System.out.print(' ');
				System.out.print(build_type);
			}
			System.out.println();
		}
	}
	protected static void cmd_release_list(EBuildType build_type) {
		for (EBuildBranch branch : EBuildBranch.values()) {
			List<URL> snaps_url = WindowsSnapshotDownloadUtil.getSnapshotURLSNewestFirst(WindowsSnapshotDownloadUtil.getDownloadURL(branch));
			for (URL snap_url : snaps_url) {
				System.out.print(branch);
				System.out.print(' ');
				System.out.print(WindowsSnapshotDownloadUtil.getRevision(snap_url));
				
				if (WindowsSnapshotDownloadUtil.hasBuildTypeAndTestPack(snap_url, build_type)) {
					System.out.print(' ');
					System.out.print(build_type);				
				}
				System.out.println();
			}
		} // end for
	}

	protected static void cmd_telemetry_pkg() {
		System.err.println("Error: Not implemented");				
	}
	
	protected static void cmd_upgrade(ConsoleManager cm, AHost host) {
		if (!host.hasCmd("git")) {
			cm.println(EPrintType.CANT_CONTINUE, "upgrade", "please install 'git' first");
			return;
		}
		
		// execute 'git pull' in c:\php-sdk\PFTT\current
		try {
			host.execElevated(cm, PfttMain.class, "git pull", AHost.FOUR_HOURS, host.getPfttDir());
		} catch ( Exception ex ) {
			cm.addGlobalException(EPrintType.CANT_CONTINUE, PfttMain.class, "cmd_upgrade", ex, "error upgrading PFTT");
		}
	}
	
	/* ------------------------------- */
	
	protected static PhpBuild newBuild(ConsoleManager cm, AHost host, String path) {
		PhpBuild build = new PhpBuild(path);
		checkDebugger(cm, host, build);
		if (build.open(cm, host))
			return build;
		build = new PhpBuild(host.getPhpSdkDir() + "/" + path);
		if (build.open(cm, host))
			return build;
		else
			return null; // build not found/readable error
	}
	
	protected static PhptSourceTestPack newTestPack(ConsoleManager cm, AHost host, String path) {
		PhptSourceTestPack test_pack = new PhptSourceTestPack(path);
		if (test_pack.open(cm, host))
			return test_pack;
		test_pack = new PhptSourceTestPack(host.getPhpSdkDir() + "/" + path);
		if (test_pack.open(cm, host))
			return test_pack;
		else
			return null; // test-pack not found/readable error
	}
	
	protected static void no_show_gui(boolean show_gui, String command) {
		if (show_gui) {
			System.out.println("PFTT: Note: -gui not supported for "+command+" (ignored)");
		}
	}
	
	protected static void checkUAC(boolean is_uac, boolean is_setup, Config config, ConsoleManager cm, EScenarioSetPermutationLayer layer) {
		if (!LocalHost.isLocalhostWindows())
			return;
		
		boolean req_uac = false;
		for ( ScenarioSet set : getScenarioSets(config, layer) ) {
			if (is_setup?set.isUACRequiredForSetup():set.isUACRequiredForStart()) {
				req_uac = true;
				break;
			}
		}
		if (is_uac||!req_uac)
			return;
		
		cm.println(EPrintType.TIP, PfttMain.class, "run pftt with -uac to avoid getting lots of UAC Dialog boxes (see -help)");
	}
	public static void readStringListFromFile(List<String> list, String filename) throws IOException {
		readStringListFromFile(list, new File(filename));
	}
	public static void readStringListFromFile(List<String> list, File file) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(file));
		String line;
		
		while ( ( line = br.readLine() ) != null ) {
			if ( StringUtil.isEmpty(line) || line.startsWith(";") || line.startsWith("#"))
				// ignore comments
				continue;
			list.add(line);
		}
		br.close();
	}
	
	/** help user find/install WinDebug properly
	 * 
	 */
	protected static void checkDebugger(ConsoleManager cm, AHost host, PhpBuild build) {
		if ((cm.isDebugAll()||cm.isDebugList()) && host.isWindows()) {
			WinDebugManager.checkIfWinDebugInstalled(host, build);
		}
	}
	
	public static void main(String[] args) throws Throwable {
		PfttMain rt = new PfttMain();
		
		//
		int args_i = 0;
		
		Config config = null;
		boolean is_uac = false, debug = false, randomize_order = false, no_result_file_for_pass_xskip_skip = false, pftt_debug = false, show_gui = false, overwrite = false, disable_debug_prompt = false, results_only = false, dont_cleanup_test_pack = false, phpt_not_in_place = false, thread_safety = true, skip_smoke_tests = false, pause = false, restart_each_test_all = false;
		int run_test_times_all = 1, delay_between_ms = 0, run_test_times_list_times = 1, run_group_times_all = 1, run_group_times_list_times = 1, max_test_read_count = 0, thread_count = 0;
		LinkedList<String> debug_list = new LinkedList<String>();
		LinkedList<String> run_test_times_list = new LinkedList<String>();
		LinkedList<String> run_group_times_list = new LinkedList<String>();
		LinkedList<String> skip_list = new LinkedList<String>();
		String source_pack = null;
		PhpDebugPack debug_pack = null;
		LinkedList<File> config_files = new LinkedList<File>();
		
		//
		for ( ; args_i < args.length ; args_i++ ) {
			if (args[args_i].equals("-gui")||args[args_i].equals("-g")) {
				show_gui = true;
			} else if (args[args_i].equals("-pause") || args[args_i].equals("-pause ")) {
				pause = true;
			} else if (args[args_i].equals("-overwrite")) {
				overwrite = true;
			} else if (args[args_i].equals("-config")||args[args_i].equals("-c")) {
				// 
				// configuration file(s) are separated by ; or : or ,
				args_i++;
				for ( String part : args[args_i].split("[;|:|,]") ) {
					// allow flexibility in the configuration file name
					//  1. add .groovy for user
					//  2. search current dir / assume filename is absolute path
					//  3. search $PFTT_DIR/conf
					//  4. search $PFTT_DIR/conf/internal
					//  5. search $PFTT_DIR/conf/app
					File config_file = new File(part);
					if (config_file.exists()) {
						if (!config_files.contains(config_file))
							config_files.add(config_file);
					} else {
						config_file = new File(part+".groovy");
						if (config_file.exists()) {
							if (!config_files.contains(config_file))
								config_files.add(config_file);
						} else {
							config_file = new File(LocalHost.getLocalPfttDir()+"/conf/"+part);
							if (config_file.exists()) {
								if (!config_files.contains(config_file))
									config_files.add(config_file);
							} else {
								config_file = new File(LocalHost.getLocalPfttDir()+"/conf/"+part+".groovy");
								if (config_file.exists()) {
									if (!config_files.contains(config_file))
										config_files.add(config_file);
								} else {
									config_file = new File(LocalHost.getLocalPfttDir()+"/conf/internal/"+part);
									if (config_file.exists()) {
										if (!config_files.contains(config_file))
											config_files.add(config_file);
									} else {
										config_file = new File(LocalHost.getLocalPfttDir()+"/conf/internal/"+part+".groovy");
										if (config_file.exists()) {
											if (!config_files.contains(config_file))
												config_files.add(config_file);
										} else {
											config_file = new File(LocalHost.getLocalPfttDir()+"/conf/app/"+part);
											if (config_file.exists()) {
												if (!config_files.contains(config_file))
													config_files.add(config_file);
											} else {
												config_file = new File(LocalHost.getLocalPfttDir()+"/conf/app/"+part+".groovy");
												if (config_file.exists()) {
													if (!config_files.contains(config_file))
														config_files.add(config_file);
												} else {
													System.err.println("User Error: config file not found: "+config_file);
													System.exit(-255);
													break;
												}
											}
										}
									}
								}
							}
						}
					}
				} // end for
			} else if (args[args_i].equals("-phpt-not-in-place")) {
				phpt_not_in_place = false;
			} else if (args[args_i].equals("-dont-cleanup-test-pack")) {
				dont_cleanup_test_pack = true;
			} else if (args[args_i].equals("-auto")) {
				// change these defaults for automated testing
				disable_debug_prompt = true;
				results_only = false;
				dont_cleanup_test_pack = false;
				phpt_not_in_place = true;
				is_uac = true;
				pause = false;
				restart_each_test_all = false;
				overwrite = true; // for rgn rl rgp rg
				no_result_file_for_pass_xskip_skip = true;
			} else if (args[args_i].equals("-randomize_order")) {
				randomize_order = true;
			} else if (args[args_i].equals("-run_test_times_all")) {
				args_i++;
				run_test_times_all = Integer.parseInt(args[args_i]);
			} else if (args[args_i].equals("-restart_each_test_all")) {
				restart_each_test_all = true;
			} else if (args[args_i].equals("-delay_between_ms")) {
				args_i++;
				delay_between_ms = Integer.parseInt(args[args_i]);
			} else if (args[args_i].equals("-thread_count")) {
				args_i++;
				thread_count = Integer.parseInt(args[args_i]);
			} else if (args[args_i].equals("-max_test_read_count")) {
				args_i++;
				max_test_read_count = Integer.parseInt(args[args_i]);
			} else if (args[args_i].equals("-skip_smoke_tests")) {
				skip_smoke_tests = true;
			} else if (args[args_i].equals("-no_nts")) {
				thread_safety = false;
			} else if (args[args_i].equals("-run_group_times_all")) {
				args_i++;
				run_group_times_all = Integer.parseInt(args[args_i]);
			} else if (args[args_i].equals("-run_group_times_list")) {
				args_i++;
				run_group_times_list_times = Integer.parseInt(args[args_i]);
				args_i++;
				readStringListFromFile(run_group_times_list, args[args_i]);
			} else if (args[args_i].equals("-run_test_times_list")) {
				args_i++;
				run_test_times_list_times = Integer.parseInt(args[args_i]);
				args_i++;
				readStringListFromFile(run_test_times_list, args[args_i]);
			} else if (args[args_i].equals("-debug_list")) {
				args_i++;
				readStringListFromFile(debug_list, args[args_i]);
			} else if (args[args_i].equals("-skip_list")) {
				args_i++;
				readStringListFromFile(skip_list, args[args_i]);
			} else if (args[args_i].startsWith("-debug_all")) {
				// also intercepted and handled by bin/pftt.cmd batch script
				debug = true;
				
			} else if (args[args_i].equals("-disable_debug_prompt")||args[args_i].equals("-debug_none")) {
				disable_debug_prompt = true; 
			} else if (args[args_i].equals("-results_only")) {
				results_only = true;
			} else if (args[args_i].startsWith("-uac")) {
				// ignore: intercepted and handled by bin/pftt.cmd batch script
				is_uac = true;
			} else if (args[args_i].startsWith("-pftt-debug")) {
				pftt_debug = true;
			} else if (args[args_i].equals("-src_pack")) {
				source_pack = args[args_i++];
			} else if (args[args_i].equals("-debug_pack")) {
				if (null == ( debug_pack = PhpDebugPack.open(rt.host, args[args_i++]))) {
					System.err.println("PFTT: debug-pack not found: "+args[args_i-1]);
					System.exit(-250);
				}
			} else if (args[args_i].equals("-h")||args[args_i].equals("--h")||args[args_i].equals("-help")||args[args_i].equals("--help")) {
				cmd_help();
				System.exit(0);
				return;
			} else if (args[args_i].startsWith("-")) {
				System.err.println("User Error: unknown option \""+args[args_i]+"\"");
				System.exit(-255);
				return;
				
			} else {
				// not option
				break;
			}
		}
		String command;
		try {
			command = args.length < args_i ? null : args[args_i].toLowerCase();
		} catch ( Exception ex ) {
			cmd_help();
			System.exit(-255);
			return;
		}
		//
		
		
		LocalConsoleManager cm = new LocalConsoleManager(source_pack, debug_pack, overwrite, debug, results_only, show_gui, disable_debug_prompt, dont_cleanup_test_pack, phpt_not_in_place, pftt_debug, no_result_file_for_pass_xskip_skip, randomize_order, run_test_times_all, 
				thread_safety, run_test_times_list_times, run_group_times_all, run_group_times_list_times, debug_list, run_test_times_list, run_group_times_list, skip_list,
				skip_smoke_tests, max_test_read_count, thread_count, restart_each_test_all, delay_between_ms);
		
		if (config_files.size()>0) {
			config = Config.loadConfigFromFiles(cm, (File[])config_files.toArray(new File[config_files.size()]));
			System.out.println("PFTT: Config: loaded "+config_files);
		} else {
			File default_config_file = new File(rt.host.getPfttDir()+"/conf/default.groovy");
			config = Config.loadConfigFromFiles(cm, default_config_file);
			System.out.println("PFTT: Config: no config files loaded... using defaults only ("+default_config_file+")");
		}
		
		if (command!=null) {
			if (command.equals("app_named")||command.equals("appnamed")||command.equals("an")) {
				// TODO
			} else if (command.equals("app_list")||command.equals("applist")||command.equals("al")) {
				if (!(args.length > args_i+2)) {
					System.err.println("User Error: must specify build and file with test names");
					System.out.println("usage: pftt app_list <path to PHP build> <file with test names>");
					System.exit(-255);
					return;
				}
				
				PhpBuild build = newBuild(cm, rt.host, args[args_i+1]);
				if (build==null) {
					System.err.println("IO Error: can not open php build: "+build);
					System.exit(-255);
					return;
				}
				cm.println(EPrintType.CLUE, PfttMain.class, "Build: "+build);
				
				File test_list_file = new File(args[args_i+1]);
				cm.println(EPrintType.CLUE, PfttMain.class, "List File: "+test_list_file);
				
				HostEnvUtil.prepareHostEnv(rt.host, cm, build, !cm.isDisableDebugPrompt());
				
				checkDebugger(cm, rt.host, build);
				build.open(cm, rt.host);
				
				PhpUnitSourceTestPack test_pack = config.getPhpUnitSourceTestPack(cm);
				cm.println(EPrintType.CLUE, PfttMain.class, "Test-Pack: "+test_pack);
				
				PhpResultPackWriter tmgr = new PhpResultPackWriter(rt.host, cm, new File(rt.host.getPhpSdkDir()), build, test_pack);
				cm.println(EPrintType.CLUE, PfttMain.class, "Writing Result-Pack: "+tmgr.getTelemetryDir());
				
				checkUAC(is_uac, false, config, cm, EScenarioSetPermutationLayer.PHP_CORE);
				
				for ( ScenarioSet scenario_set : getScenarioSets(config, EScenarioSetPermutationLayer.WEB_APPLICATION)) {
				
					List<AHost> hosts = config.getHosts();
					AHost host = hosts.isEmpty()?rt.host:hosts.get(0);
					LocalPhpUnitTestPackRunner r = new LocalPhpUnitTestPackRunner(cm, tmgr, scenario_set, build, host, rt.host);
					LinkedList<String> test_names = new LinkedList<String>();
					readStringListFromFile(test_names, test_list_file);
					// TODO test_pack.read(test_cases, cm, twriter, build)
					// TODO r.runTestList(test_pack, test_cases);
					tmgr.getPhpUnit(host, scenario_set).close();
					
				} // end for
				
				tmgr.close();
			} else if (command.equals("app_all")||command.equals("appall")||command.equals("aa")) {
				if (!(args.length > args_i+1)) {
					System.err.println("User Error: must specify build");
					System.out.println("usage: pftt app_all <path to PHP build>");
					System.exit(-255);
					return;
				}
				
				PhpBuild build = newBuild(cm, rt.host, args[args_i+1]);
				if (build==null) {
					System.err.println("IO Error: can not open php build: "+build);
					System.exit(-255);
					return;
				}
				cm.println(EPrintType.CLUE, PfttMain.class, "Build: "+build);
				
				HostEnvUtil.prepareHostEnv(rt.host, cm, build, !cm.isDisableDebugPrompt());
				
				checkDebugger(cm, rt.host, build);
				build.open(cm, rt.host);
				
				PhpUnitSourceTestPack test_pack = config.getPhpUnitSourceTestPack(cm);
				cm.println(EPrintType.CLUE, PfttMain.class, "Test-Pack: "+test_pack);
				
				PhpResultPackWriter tmgr = new PhpResultPackWriter(rt.host, cm, new File(rt.host.getPhpSdkDir()), build, test_pack);
				cm.println(EPrintType.CLUE, PfttMain.class, "Writing Result-Pack: "+tmgr.getTelemetryDir());
				
				checkUAC(is_uac, false, config, cm, EScenarioSetPermutationLayer.PHP_CORE);
				
				for (ScenarioSet scenario_set : getScenarioSets(config, EScenarioSetPermutationLayer.WEB_APPLICATION)) {
					List<AHost> hosts = config.getHosts();
					AHost host = hosts.isEmpty()?rt.host:hosts.get(0);
					LocalPhpUnitTestPackRunner r = new LocalPhpUnitTestPackRunner(cm, tmgr, scenario_set, build, host, rt.host);
					r.runAllTests(test_pack);
					tmgr.getPhpUnit(host, scenario_set).close();
				}
				
				tmgr.close();
			} else if (command.equals("core_named")||command.equals("corenamed")||command.equals("cornamed")||command.equals("coren")||command.equals("cn")) {
				if (!(args.length > args_i+3)) {
					System.err.println("User Error: must specify build, test-pack and name(s) and/or name fragment(s)");
					System.out.println("usage: pftt core_named <path to PHP build> <path to PHPT test-pack> <test case names or name fragments (separated by spaces)>");
					System.exit(-255);
					return;
				}
				
				PhpBuild build = newBuild(cm, rt.host, args[args_i+1]);
				if (build==null) {
					System.err.println("IO Error: can not open php build: "+build);
					System.exit(-255);
					return;
				}
				
				PhptSourceTestPack test_pack = newTestPack(cm, rt.host, args[args_i+2]);
				if (test_pack==null) {
					System.err.println("IO Error: can not open php test pack: "+test_pack);
					System.exit(-255);
					return;
				}				
				args_i += 3; // skip over build and test_pack
				
				checkUAC(is_uac, false, config, cm, EScenarioSetPermutationLayer.PHP_CORE);
				
				// read name fragments from CLI arguments
				ArrayList<String> names = new ArrayList<String>(args.length-args_i);
				for ( ; args_i < args.length ; args_i++) 
					names.add(args[args_i]);
				
				cm.println(EPrintType.IN_PROGRESS, "Build", build.toString());
				cm.println(EPrintType.IN_PROGRESS, "Test-Pack", test_pack.toString());
				
				HostEnvUtil.prepareHostEnv(rt.host, cm, build, !cm.isDisableDebugPrompt());
				cmd_core_named(rt, cm, config, build, test_pack, names);
				
				System.out.println("PFTT: finished");
			} else if (command.equals("core_list")||command.equals("corelist")||command.equals("corlist")||command.equals("corel")||command.equals("cl")) {
				if (!(args.length > args_i+3)) {
					System.err.println("User Error: must specify build, test-pack and list file");
					System.out.println("usage: list file must contain plain-text list names of tests to execute");
					System.out.println("usage: pftt core_list <path to PHP build> <path to PHPT test-pack> <list file>");
					System.exit(-255);
					return;
				}
				
				PhpBuild build = newBuild(cm, rt.host, args[args_i+1]);
				if (build == null) {
					System.err.println("IO Error: can not open php build: "+build);
					System.exit(-255);
					return;
				}
				
				PhptSourceTestPack test_pack = newTestPack(cm, rt.host, args[args_i+2]);
				if (test_pack == null) {
					System.err.println("IO Error: can not open php test pack: "+test_pack);
					System.exit(-255);
					return;
				}
				
				File list_file = new File(args[args_i+3]);
				if (!list_file.isFile()) {
					System.err.println("IO Error: list file not found: "+list_file);
					System.exit(-255);
					return;
				}
				
				checkUAC(is_uac, false, config, cm, EScenarioSetPermutationLayer.PHP_CORE);
				
				cm.println(EPrintType.IN_PROGRESS, "Build", build.toString());
				cm.println(EPrintType.IN_PROGRESS, "Test-Pack", test_pack.toString());
				
				HostEnvUtil.prepareHostEnv(rt.host, cm, build, !cm.isDisableDebugPrompt());
				cmd_core_list(rt, cm, config, build, test_pack, list_file);		
				
				System.out.println("PFTT: finished");
			} else if (command.equals("phpt_repro")||command.equals("phpt_replay")||command.equals("phpt_re")||command.equals("phptrepro")||command.equals("phptreplay")||command.equals("phptre")||command.equals("pr")) {
				// TODO
				
				// TODO if -c gives config file(s) different from result-pack, show warning
				
			} else if (command.equals("core_all")||command.equals("coreall")||command.equals("corall")||command.equals("corea")||command.equals("ca")) {
				if (!(args.length > args_i+2)) {
					System.err.println("User Error: must specify build and test-pack");
					System.out.println("usage: pftt core_all <path to PHP build> <path to PHPT test-pack>");
					System.exit(-255);
					return;
				}
				
				PhpBuild build = newBuild(cm, rt.host, args[args_i+1]);
				if (build == null) {
					System.err.println("IO Error: can not open php build: "+build);
					System.exit(-255);
					return;
				}
				
				PhptSourceTestPack test_pack = newTestPack(cm, rt.host, args[args_i+2]);
				if (test_pack == null) {
					System.err.println("IO Error: can not open php test pack: "+test_pack);
					System.exit(-255);
					return;
				}
				
				cm.println(EPrintType.IN_PROGRESS, "Main", "Testing all PHPTs in test pack...");
				cm.println(EPrintType.IN_PROGRESS, "Build", build.toString());
				cm.println(EPrintType.IN_PROGRESS, "Test-Pack", test_pack.toString());
				
				checkUAC(is_uac, false, config, cm, EScenarioSetPermutationLayer.PHP_CORE);
				
				// run all tests
				HostEnvUtil.prepareHostEnv(rt.host, cm, build, !cm.isDisableDebugPrompt());
				cmd_core_all(rt, cm, config, build, test_pack);
				
				System.out.println("PFTT: finished");
			} else if (command.equals("setup")||command.equals("set")||command.equals("setu")) {
				if (!(args.length > args_i+1)) {
					System.err.println("User Error: must build");
					System.out.println("usage: pftt setup <path to PHP build>");
					System.exit(-255);
					return;
				}
				
				PhpBuild build = newBuild(cm, rt.host, args[args_i+1]);
				if (build == null) {
					System.err.println("IO Error: can not open php build: "+build);
					System.exit(-255);
					return;
				}
				
				checkUAC(is_uac, true, config, cm, EScenarioSetPermutationLayer.WEB_SERVER);
				
				// setup all scenarios
				for ( ScenarioSet set : getScenarioSets(config, EScenarioSetPermutationLayer.WEB_SERVER) ) {
					for ( Scenario scenario : set ) {
						if (scenario.isImplemented()) {
							if (scenario.setup(cm, rt.host, build, set)) {
								cm.println(EPrintType.COMPLETED_OPERATION, "Setup", "setup successful: "+scenario);
								switch(scenario.start(cm, rt.host, build, set)) {
								case STARTED:
									cm.println(EPrintType.COMPLETED_OPERATION, "Setup", "Started: "+scenario);
									break;
								case FAILED_TO_START:
									cm.println(EPrintType.CANT_CONTINUE, "Setup", "Error starting: "+scenario);
									break;
								case SKIP:
									break;
								default:
									break;
								}
							} else {
								cm.println(EPrintType.CANT_CONTINUE, "Setup", "setup failed: "+scenario);
							}
						} else {
							cm.println(EPrintType.CANT_CONTINUE, "Setup", "Skipping scenario, not implemented: "+scenario);
						}
					} // end for
				}
			} else if (command.equals("list")||command.equals("ls")) {
				checkUAC(is_uac, false, config, cm, EScenarioSetPermutationLayer.PHP_CORE);
				for ( ScenarioSet set : getScenarioSets(config, EScenarioSetPermutationLayer.PHP_CORE) ) {
					cm.println(EPrintType.IN_PROGRESS, "List", set.toString());
				}
			} else if (command.equals("shell_ui")||(show_gui && command.equals("shell"))) {
				cmd_shell_ui();
			} else if (command.equals("shell")) {
				no_show_gui(show_gui, command);
				cmd_shell();				
			} else if (command.equals("exec")) {
				checkUAC(is_uac, false, config, cm, EScenarioSetPermutationLayer.PHP_CORE);
				no_show_gui(show_gui, command);
				cmd_exec();
			} else if (command.equals("ui")) {
				no_show_gui(show_gui, command);
				cmd_ui();
			} else if (command.equals("perf")) {
				checkUAC(is_uac, false, config, cm, EScenarioSetPermutationLayer.PHP_CORE);
				cmd_perf();
			} else if (command.equals("release_get")||command.equals("rgn")||command.equals("rgnew")||command.equals("rgnewest")||command.equals("rgp")||command.equals("rgprev")||command.equals("rgprevious")||command.equals("rg")||command.equals("rget")) {
				EBuildBranch branch = null;
				EBuildType build_type = null;
				String revision = null;
				URL url = null;
				if (command.equals("rgn")||command.equals("rgnew")||command.equals("rgnewest"))
					revision = "newest";
				else if (command.equals("rgp")||command.equals("rgprev")||command.equals("rgprevious"))
					revision = "previous";
				
				for ( ; args_i < args.length && ( branch == null || build_type == null || revision == null ) ; args_i++ ) {
					if (branch==null)
						branch = EBuildBranch.guessValueOf(args[args_i]);
					if (build_type==null)
						build_type = EBuildType.guessValueOf(args[args_i]);
					if (revision==null&&args[args_i].startsWith("r"))
						revision = args[args_i];
					else if (args[args_i].equals("previous")||args[args_i].equals("prev")||args[args_i].equals("p"))
						revision = "previous";
					else if (args[args_i].equals("newest")||args[args_i].equals("new")||args[args_i].equals("n"))
						revision = "newest";
					else if (args[args_i].startsWith("http://"))
						url = new URL(args[args_i]);
				}
				
				if (url==null&&(branch==null||build_type==null)) {
					System.err.println("User error: must specify branch, build-type (NTS or TS) and revision");
					System.err.println("Usage: pftt release_get <branch> <build-type> [r<revision>|newest|previous]");
					System.err.println("Usage: pftt release_get <URL>");
					System.err.println("Branch can be any of: "+StringUtil.toString(EBuildBranch.values()));
					System.err.println("Build Type can be any of: "+StringUtil.toString(EBuildType.values()));
					System.exit(-255);
					return;
				} else if (url==null&&revision==null) {
					System.err.println("User error: must specify branch, build-type (NTS or TS) and revision");
					System.err.println("Usage: pftt release_get <branch> <build-type> [r<revision>|newest|previous]");
					System.err.println("Usage: pftt release_get <URL>");
					System.err.println("Revision must start with 'r'");
					System.exit(-255);
					return;
				} else {
					no_show_gui(show_gui, command);
					
					// input processed, dispatch
					if (url!=null)
						cmd_release_get(cm, overwrite, rt.host, url);
					else if (revision.equals("newest"))
						cmd_release_get_newest(cm, overwrite, rt.host, branch, build_type);
					else if (revision.equals("previous"))
						cmd_release_get_previous(cm, overwrite, rt.host, branch, build_type);
					else
						cmd_release_get_revision(cm, overwrite, rt.host, branch, build_type, revision);
				}
			} else if (command.equals("release_list")||command.equals("rl")||command.equals("rlist")) {
				EBuildBranch branch = null;
				EBuildType build_type = null;
				for ( ; args_i < args.length && ( branch == null || build_type == null ) ; args_i++ ) {
					if (branch==null)
						branch = EBuildBranch.guessValueOf(args[args_i]);
					if (build_type==null)
						build_type = EBuildType.guessValueOf(args[args_i]);
				}
				no_show_gui(show_gui, command);

				// dispatch
				if (branch==null) {
					if (build_type==null) {
						System.out.println("PFTT: listing all snapshot releases (newest first)");
						cmd_release_list();
					} else {
						System.out.println("PFTT: listing all snapshot releases of "+build_type+" builds (newest first)");
						cmd_release_list(build_type);						
					}
				} else {
					if (build_type==null) {
						System.out.println("PFTT: listing all snapshot releases from "+branch+" (newest first)");
						cmd_release_list(branch);
					} else {
						System.out.println("PFTT: listing all snapshot releases from "+branch+" of "+build_type+" builds  (newest first)");
						cmd_release_list(build_type, branch);
					}
				}	
			} else if (command.equals("telemetry_pkg")) {
				no_show_gui(show_gui, command);
				
				cmd_telemetry_pkg();
			} else if (command.equals("smoke")) {
				no_show_gui(show_gui, command);
				
				cmd_smoke();
			} else if (command.equals("upgrade")) {
				no_show_gui(show_gui, command);
				
				cmd_upgrade(cm, rt.host);
			} else if (command.equals("help")) {
				no_show_gui(show_gui, command);
				
				cmd_help();
			} else if (command.equals("cmp-report")) {
				PhpResultPack base_pack = PhpResultPackReader.open(cm, rt.host, new File("C:\\php-sdk\\PHP_5_5-Result-Pack-5.5.0alpha6-TS-X86-VC11-5"));
				PhpResultPack test_pack = PhpResultPackReader.open(cm, rt.host, new File("C:\\php-sdk\\PHP_5_5-Result-Pack-5.5.0alpha6-TS-X86-VC11-5"));
			
				for ( AbstractPhpUnitRW base : base_pack.getPhpUnit() ) {
					for ( AbstractPhpUnitRW test : test_pack.getPhpUnit() ) {
						PhpUnitReportGen php_unit_report = new PhpUnitReportGen(base, test);
						String html_str = php_unit_report.getHTMLString(cm, false);

						File html_file = new File("c:\\php-sdk\\php_unit_report.html");
						FileWriter fw = new FileWriter(html_file);
						fw.write(html_str);
						fw.close();
						Desktop.getDesktop().browse(html_file.toURI());
					}
				}
				
				for ( AbstractPhptRW base : base_pack.getPHPT() ) {
					for ( AbstractPhptRW test : test_pack.getPHPT() ) {
						PHPTReportGen phpt_report = new PHPTReportGen(base, test);
						String html_str = phpt_report.getHTMLString(cm, false);

						File html_file = new File("c:\\php-sdk\\phpt_report.html");
						FileWriter fw = new FileWriter(html_file);
						fw.write(html_str);
						fw.close();
						Desktop.getDesktop().browse(html_file.toURI());
					}
				}
				
			} else {
				no_show_gui(show_gui, command);
				
				cmd_help();
			}
		} else {		
			cmd_help();
		}
		if (pause) {
			if (!cm.isResultsOnly())
				System.out.println("PFTT: Press enter to exit...");
			System.in.read();
		}
		if (!show_gui)
			// ensure all threads end
			System.exit(0);
	} // end public static void main
	
} // end class RunTests
