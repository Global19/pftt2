package com.mostc.pftt.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;

import com.github.mattficken.io.IOUtil;
import com.mostc.pftt.host.Host;
import com.mostc.pftt.model.phpt.EPhptSection;
import com.mostc.pftt.model.phpt.EPhptTestStatus;
import com.mostc.pftt.model.phpt.ESAPIType;
import com.mostc.pftt.model.phpt.PhpBuild;
import com.mostc.pftt.model.phpt.PhpIni;
import com.mostc.pftt.model.phpt.PhptTestCase;
import com.mostc.pftt.model.phpt.PhptSourceTestPack;
import com.mostc.pftt.model.phpt.PhptActiveTestPack;
import com.mostc.pftt.model.sapi.WebServerInstance;
import com.mostc.pftt.model.sapi.WebServerManager;
import com.mostc.pftt.results.ConsoleManager;
import com.mostc.pftt.results.IPhptTestResultReceiver;
import com.mostc.pftt.results.PhptTestResult;
import com.mostc.pftt.runner.LocalPhptTestPackRunner.PhptThread;
import com.mostc.pftt.scenario.ScenarioSet;
import com.mostc.pftt.util.ErrorUtil;
import com.mostc.pftt.util.StringUtil;

/** Runs PHPT Test Cases against PHP while its running under a Web Server (builtin, IIS or Apache)
 * 
 * @author Matt Ficken 
 *
 */

// TODO error msg should tell how many times web server was restarted
public class HttpTestCaseRunner extends AbstractPhptTestCaseRunner2 {
	protected final WebServerManager smgr;
	protected final ByteArrayOutputStream request_bytes, response_bytes;
	protected WebServerInstance web = null;
	protected String cookie_str;
	protected final HttpParams params;
	protected final HttpProcessor httpproc;
	protected final HttpRequestExecutor httpexecutor;

	public HttpTestCaseRunner(PhpIni ini, Map<String,String> env, HttpParams params, HttpProcessor httpproc, HttpRequestExecutor httpexecutor, WebServerManager smgr, WebServerInstance web, PhptThread thread, PhptTestCase test_case, ConsoleManager cm, IPhptTestResultReceiver twriter, Host host, ScenarioSet scenario_set, PhpBuild build, PhptSourceTestPack src_test_pack, PhptActiveTestPack active_test_pack) {
		super(ini, thread, test_case, cm, twriter, host, scenario_set, build, src_test_pack, active_test_pack);
		this.params = params;
		this.httpproc = httpproc;
		this.httpexecutor = httpexecutor;
		this.smgr = smgr;
		this.web = web;
		// CRITICAL: need this to get ENV from this TestCaseGroup
		if (env!=null && ((env.containsKey("TEMP")&&env.get("TEMP").equals(".")) || (env.containsKey("TMP")&&env.get("TMP").equals(".")))) {
			// checks for case like: ext/phar/commit/tar/phar_commitwrite.phpt
			this.env = new HashMap<String,String>(7);
			this.env.putAll(env);
			// TODO
			this.env.put("TEMP", active_test_pack.getDirectory()+"/"+Host.dirname(test_case.getName()));
			this.env.put("TMP", active_test_pack.getDirectory()+"/"+Host.dirname(test_case.getName()));
		} else {
			this.env = env;
		}
		//
		
		this.request_bytes = new ByteArrayOutputStream(256);
		this.response_bytes = new ByteArrayOutputStream(4096);
	}
	
	/** @see AbstractSAPIScenario#willSkip
	 * 
	 * @param twriter
	 * @param host
	 * @param scenario_set
	 * @param type
	 * @param build
	 * @param test_case
	 * @return
	 * @throws Exception
	 */
	public static boolean willSkip(ConsoleManager cm, IPhptTestResultReceiver twriter, Host host, ScenarioSet scenario_set, ESAPIType type, PhpBuild build, PhptTestCase test_case) throws Exception {
		if (AbstractPhptTestCaseRunner.willSkip(cm, twriter, host, scenario_set, type, build, test_case)) {
			return true;
		} else if (test_case.containsSection(EPhptSection.STDIN)) {
			twriter.addResult(host, scenario_set, new PhptTestResult(host, EPhptTestStatus.XSKIP, test_case, "STDIN section not supported for testing against web servers", null, null, null, null, null, null, null, null, null, null, null));
			
			return true;
		} else if (test_case.containsSection(EPhptSection.ARGS)) {
			twriter.addResult(host, scenario_set, new PhptTestResult(host, EPhptTestStatus.XSKIP, test_case, "ARGS section not supported for testing against web servers", null, null, null, null, null, null, null, null, null, null, null));
			
			return true;
		} else if (cm.isDisableDebugPrompt()&&test_case.isNamed(
					// causes a blocking winpopup msg about a few php_*.dll DLLs that couldn't be loaded
					// (ignore these for automated testing, but still show them for manual testing)
					"ext/zlib/tests/008.phpt",
					"ext/zlib/tests/ob_gzhandler_legacy_002.phpt"
				)) {
			twriter.addResult(host, scenario_set, new PhptTestResult(host, EPhptTestStatus.XSKIP, test_case, "test shows blocking winpopup msg", null, null, null, null, null, null, null, null, null, null, null));
			
			return true;
		} else if (test_case.isNamed(
				// fpassthru() system() and exec() doesn't run on Apache
				"ext/standard/tests/popen_pclose_basic-win32.phpt", 
				"sapi/cli/tests/bug61546.phpt",
				"ext/standard/tests/file/bug41874.phpt",
				"ext/standard/tests/file/bug41874_1.phpt",
				"ext/standard/tests/file/bug41874_2.phpt",
				"ext/standard/tests/file/bug41874_3.phpt",
				"ext/standard/tests/file/popen_pclose_basic-win32.phpt",
				// changing memory limit under mod_php after script started is N/A
				"tests/lang/bug45392.phpt",
				// this test will return different output on apache/iis
				"ext/standard/tests/general_functions/get_cfg_var_variation8.phpt",
				"tests/basic/bug54514.phpt",
				"sapi/tests/test005.phpt",
				//////////////////
				"ext/standard/tests/strings/004.phpt",
				"ext/mbstring/tests/bug63447_001.phpt",
				"ext/mbstring/tests/bug63447_002.phpt",
				"ext/iconv/tests/ob_iconv_handler.phpt",
				"ext/mbstring/tests/mb_strcut.phpt",
				"ext/mbstring/tests/mb_decode_numericentity.phpt",
				//////////////////
				"ext/standard/tests/file/parse_ini_file.phpt",
				"tests/basic/rfc1867_missing_boundary.phpt",
				"ext/xml/tests/xml006.phpt",
				"zend/tests/bug48930.phpt",
				"ext/json/tests/002.phpt",
				"ext/zlib/tests/bug55544-win.phpt",
				"tests/basic/025.phpt",
				"ext/standard/tests/array/bug34066_1.phpt",
				"tests/basic/rfc1867_invalid_boundary.phpt",
				"zend/tests/bug54268.phpt",
				"tests/basic/rfc1867_post_max_size.phpt",
				"ext/dom/tests/bug37456.phpt",
				"ext/libxml/tests/bug61367-read.phpt",
				"zend/tests/multibyte/multibyte_encoding_003.phpt",
				"ext/standard/tests/general_functions/002.phpt",
				"zend/tests/multibyte/multibyte_encoding_002.phpt",
				"tests/basic/rfc1867_garbled_mime_headers.phpt",
				"ext/standard/tests/array/bug34066.phpt",
				"ext/standard/tests/general_functions/006.phpt",
				"ext/libxml/tests/bug61367-write.phpt",
				"ext/session/tests/rfc1867_invalid_settings-win.phpt",
				"ext/session/tests/rfc1867_invalid_settings_2-win.phpt",
				"ext/standard/tests/versioning/php_sapi_name_variation001.phpt",
				"ext/standard/tests/math/rad2deg_variation.phpt",
				"ext/standard/tests/strings/strtoupper.phpt",
				"ext/standard/tests/strings/sprintf_variation47.phpt",
				"ext/standard/tests/general_functions/bug41445_1.phpt",
				"ext/standard/tests/strings/htmlentities.phpt",
				"ext/standard/tests/strings/fprintf_variation_001.phpt",
				"ext/standard/tests/general_functions/var_dump.phpt",
				"ext/session/tests/003.phpt",
				"ext/session/tests/023.phpt",
				"ext/standard/tests/streams/stream_get_meta_data_socket_variation3.phpt",
				"ext/standard/tests/streams/stream_get_meta_data_socket_variation4.phpt",
				/////////////////
				// getopt returns false under web server (ok)
				"ext/standard/tests/general_functions/bug43293_1.phpt",
				"ext/standard/tests/general_functions/bug43293_2.phpt",
				// fopen("stdout") not supported under apache
				"tests/strings/002.phpt"
				)) {
			twriter.addResult(host, scenario_set, new PhptTestResult(host, EPhptTestStatus.XSKIP, test_case, "test is not valid on web servers", null, null, null, null, null, null, null, null, null, null, null));
			
			return true;
		} else if (host.isWindows() && test_case.isNamed(
			// on Windows/Apache, already start with output buffering
			// so the expected output is different (but is not a bug)
			"tests/output/ob_get_level_basic_001.phpt",
			"tests/output/ob_get_length_basic_001.phpt",
			"tests/output/ob_clean_basic_001.phpt",
			"tests/output/ob_get_status.phpt",
			"tests/output/ob_010.phpt",
			"tests/output/ob_011.phpt",
			"tests/output/bug60321.phpt",
			"ext/phar/tests/phar_create_in_cwd.phpt",
			"ext/phar/tests/phar_commitwrite.phpt",
			"tests/output/ob_start_error_005.phpt")) {
			twriter.addResult(host, scenario_set, new PhptTestResult(host, EPhptTestStatus.XSKIP, test_case, "test is not valid on web servers", null, null, null, null, null, null, null, null, null, null, null));
			
			return true;
		} else {
			return false;
		}
	} // end public static boolean willSkip
	
	@Override
	protected void prepareTest() throws Exception {
		super.prepareTest();
		
		cookie_str = test_case.get(EPhptSection.COOKIE);
	}
	
	/** executes SKIPIF, TEST or CLEAN over http.
	 * 
	 * retries request if it times out and restarts web server if it crashes
	 * 
	 * @param path
	 * @param section
	 * @return
	 * @throws Exception
	 */
	protected String http_execute(String path, EPhptSection section) throws Exception {
		try {
			try {
				return do_http_execute(path, section, false);
			} catch ( IOException ex1 ) { // SocketTimeoutException or ConnectException
				if (cm.isPfttDebug()) {
					ex1.printStackTrace();
				}
				
				// notify of crash so it gets reported everywhere
				web.notifyCrash("PFTT: timeout during test("+section+" SECTION): "+test_case.getName()+"\n"+ErrorUtil.toString(ex1), 0);
				// ok to close this here, since its not an Access Violation(AV) and so won't prompt
				// the user to enter Visual Studio, WinDbg or GDB
				web.close(); 
				
				cm.restartingAndRetryingTest(test_case);
				
				// get #do_http_execute to make a new server
				// this will make a new WebServerInstance that will only be used to run this 1 test
				// (so other tests will not interfere with this test at all)
				web = null; 
				return do_http_execute(path, section, true);
			}
		} catch ( IOException ioe ) {
			String ex_str = ErrorUtil.toString(ioe);
			
			// notify web server that it crashed. it will record this, which will be accessible
			// with WebServerInstance#getSAPIOutput (will be recorded by PhptTelemetryWriter)
			web.notifyCrash("PFTT: IOException during test("+section+" SECTION): "+test_case.getName()+"\n"+ex_str, 0);
			
			// generate a failure string here too though, so that this TEST or SKIPIF section is marked as a failure
			StringBuilder sb = new StringBuilder(512);
			sb.append("PFTT: couldn't connect to server after One Minute\n");
			sb.append("PFTT: created new server only for running this test which did not respond after another One Minute timeout\n");
			sb.append("PFTT: was trying to run ("+section+" section of): ");
			sb.append(test_case.getName());
			sb.append("\n");
			sb.append("PFTT: these two lists refer only to second server (created for specifically for only this test)\n");
			web.getActiveTestListString(sb);
			web.getAllTestListString(sb);
			
			// if TEST, runner will evaluate this as a failure
			// if SKIPIF, runner will not skip test and will try to run it
			//
			// both are the most ideal behavior possible in this situation
			//
			// normally this shouldn't happen, so checking a string once in a while is faster than
			//     setting a flag here and checking that flag for every test in #evalTest
			return sb.toString();
		}
	} // end protected String http_execute
	

	protected String do_http_execute(String path, EPhptSection section, boolean is_replacement) throws Exception {
		path = Host.toUnixPath(path);
		if (path.startsWith(Host.toUnixPath(active_test_pack.getDirectory())))
			path = path.substring(active_test_pack.getDirectory().length());
		if (!path.startsWith("/"))
			path = "/" + path;
		
		try {
			if (web!=null) {
				synchronized(web) {
					WebServerInstance _web = smgr.getWebServerInstance(cm, host, build, ini, env, active_test_pack.getDirectory(), web, test_case);
					if (_web!=this.web) {
						this.web = _web;
						is_replacement = true;
					
						if (web==null||web.isCrashed()) {
							markTestAsCrash();
							
							// test will fail (because this(`PFTT: server...`) is the actual output which won't match the expected output)
							//
							// return server's crash output and an additional message about this test
							return web.getSAPIOutput() + "PFTT: server crashed already (server was created to replace a crashed web server. server was created to run this 1 test and didn't run any other tests before this one), didn't bother trying to execute test: "+test_case.getName();
						}
					}
				} // end sync
			}
			if (web==null) {
				// test should be a FAIL or CRASH
				// its certainly the fault of a test (not PFTT) if not this test
				this.web = smgr.getWebServerInstance(cm, host, build, ini, env, active_test_pack.getDirectory(), web, test_case);
				
				if (web==null||web.isCrashed()) {
					markTestAsCrash();
					
					return "PFTT: no web server available!\n";
				}
			}
				
			// CRITICAL: keep track of test cases running on web server
			web.notifyTestPreRequest(test_case);
			
			if (stdin_post==null || section != EPhptSection.TEST)
				return do_http_get(path);
			
			// only do POST for TEST sections where stdin_post!=null
			return do_http_post(path);
		} finally {
			// CRITICAL: keep track of test cases running on web server
			if (web!=null) {
				web.notifyTestPostResponse(test_case);
			
				if (web.isCrashed())
					markTestAsCrash();
				if (is_replacement && (cm.isDisableDebugPrompt()||!web.isCrashed()||!host.isWindows())) {
					// CRITICAL: if this WebServerInstance is a replacement, then it exists only within this specific HttpTestCaseRunner
					// instance. if it is not terminated here, it will keep running forever!
					//
					// don't close crashed servers on windows unless WER popup is disabled because user may want to
					// debug them. if user doesn't, they'll click close in WER popup
					web.close();
				}
			
			}
		}
	}
	
	protected void markTestAsCrash() {
		not_crashed = false; // @see #runTest
		
		twriter.addResult(host, scenario_set, new PhptTestResult(host, EPhptTestStatus.CRASH, test_case, null, null, null, null, ini, env, null, stdin_post, null, null, null, null, web==null?null:web.getSAPIOutput()));
	}
		
	protected DebuggingHttpClientConnection conn;
	protected String do_http_get(String path) throws Exception {
		return do_http_get(path, 0);
	}
	
	protected String do_http_get(String path, int i) throws Exception {
		HttpContext context = new BasicHttpContext(null);
		HttpHost http_host = new HttpHost(web.hostname(), web.port());
		
		if (conn!=null) {
			conn.close();
			conn = null;
		}
		conn = new DebuggingHttpClientConnection(request_bytes, response_bytes);
		try {
			context.setAttribute(ExecutionContext.HTTP_CONNECTION, conn);
			context.setAttribute(ExecutionContext.HTTP_TARGET_HOST, http_host);
			
			Socket socket = new Socket(http_host.getHostName(), http_host.getPort());
			conn.bind(socket, params);
			conn.setSocketTimeout(60*1000);
			
			HttpGet request = new HttpGet(path);
			if (cookie_str!=null)
				request.setHeader("Cookie", cookie_str);
			// CRITICAL: tell web server to return plain-text (not HTMl) 
			// for some reason(w/o this), apache returns HTML formatted responses for tests like ext/standard/tests/array/rsort.phpt
			request.setHeader("Accept", "text/plain");
			request.setParams(params);
			
			httpexecutor.preProcess(request, httpproc, context);
			
			HttpResponse response = httpexecutor.execute(request, conn, context);
			
			response.setParams(params);
			httpexecutor.postProcess(response, httpproc, context);
			
			//
			// support for HTTP redirects: used by some PHAR tests
			if (i<10) {
				Header lh = response.getFirstHeader("Location");
				if (lh!=null) {
					return do_http_get(lh.getValue(), i+1);
				}
			}
			//
			
			return IOUtil.toString(response.getEntity().getContent(), IOUtil.HALF_MEGABYTE);
		} finally {
			conn.close();
		}
	} // end protected String do_http_get
	
	protected String do_http_post(String path) throws Exception {
		return do_http_post(path, 0);
	}
	
	protected String do_http_post(String path, int i) throws Exception {
		HttpContext context = new BasicHttpContext(null);
		HttpHost http_host = new HttpHost(web.hostname(), web.port());
		
		if (conn!=null) {
			conn.close();
			conn = null;
		}
		conn = new DebuggingHttpClientConnection(request_bytes, response_bytes);
		try {
			context.setAttribute(ExecutionContext.HTTP_CONNECTION, conn);
			context.setAttribute(ExecutionContext.HTTP_TARGET_HOST, http_host);
			
			Socket socket = new Socket(http_host.getHostName(), http_host.getPort());
			conn.bind(socket, params);
			conn.setSocketTimeout(60*1000);
			
			HttpPost request = new HttpPost(path);
			if (content_type!=null)
				request.setHeader("Content-Type", content_type);
			if (cookie_str!=null)
				request.setHeader("Cookie", cookie_str);
			request.setParams(params);
			request.setEntity(new ByteArrayEntity(stdin_post));
			
			httpexecutor.preProcess(request, httpproc, context);		
			
			HttpResponse response = httpexecutor.execute(request, conn, context);
			
			response.setParams(params);
			httpexecutor.postProcess(response, httpproc, context);
			
			//
			// support for HTTP redirects: used by some PHAR tests
			if (i<10) {
				Header lh = response.getFirstHeader("Location");
				if (lh!=null) {
					return do_http_post(lh.getValue(), i+1);
				}
			}
			//
			
			return IOUtil.toString(response.getEntity().getContent(), IOUtil.HALF_MEGABYTE);
		} finally {
			conn.close();
		}
	} // end protected String do_http_post
	
	@Override
	protected PhptTestResult notifyFail(PhptTestResult result) {
		if (conn==null)
			return super.notifyFail(result);
		
		// store the http request(s) and response(s) used in this test to help user diagnose the failure
		
		result.http_request = request_bytes.toString();
		result.http_response = response_bytes.toString();
		
		return super.notifyFail(result);
	}
	
	@Override
	protected String executeSkipIf() throws Exception {
		return http_execute(skipif_file, EPhptSection.SKIPIF);
	}

	@SuppressWarnings("deprecation")
	@Override
	protected String executeTest() throws Exception {
		String request_uri = this.test_file;
		
		if (env!=null&&env.containsKey("REQUEST_URI")) {
			// ex: ext/phar/tests/frontcontroller17.phpt
			request_uri = Host.dirname(request_uri)+"/"+env.get("REQUEST_URI");
		}
		
		if (test_case.containsSection(EPhptSection.GET)) {
			String query_string = test_case.getTrim(EPhptSection.GET);
			// query_string needs to be added to the GET path
			if (StringUtil.isNotEmpty(query_string)) {
				// a good, complex example for this is ext/filter/tests/004.skip.php
				// it puts HTML tags and other illegal chars in query_string (uses both HTTP GET and POST)
				//
				// illegal chars need to be URL-Encoded (percent-encoding, escaped)... 
				// this is NOT the same as escaping entities in HTML
				//
				// @see https://en.wikipedia.org/wiki/Percent-encoding
				// @see https://en.wikipedia.org/wiki/List_of_XML_and_HTML_character_entity_references
				//
				String[] names_and_values = query_string.split("[&|\\=]");
				StringBuilder query_string_sb = new StringBuilder();
				for ( int i=0 ; i < names_and_values.length ; i+=2 ) {
					if (query_string_sb.length()>0)
						query_string_sb.append('&');
					query_string_sb.append(names_and_values[i]);
					query_string_sb.append('=');
					if (names_and_values.length>i+1)
						query_string_sb.append(URLEncoder.encode(names_and_values[i+1]));
				}
				
				request_uri = test_file + "?" + query_string_sb;
			}
		} // end if containsSection(GET)
		
		return http_execute(request_uri, EPhptSection.TEST);
	}

	@Override
	protected void executeClean() throws Exception {
		http_execute(test_clean, EPhptSection.CLEAN);
	}

	@Override
	protected String getCrashedSAPIOutput() {
		return web!=null&&web.isCrashed() ? web.getSAPIOutput() : null;
	}

	@Override
	protected String[] splitCmdString() {
		return web==null?StringUtil.EMPTY_ARRAY:web.getCmdArray();
	}

} // end public class HttpTestCaseRunner
