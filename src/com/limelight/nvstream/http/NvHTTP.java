package com.limelight.nvstream.http;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Scanner;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.limelight.LimeLog;
import com.limelight.nvstream.ConnectionContext;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;


public class NvHTTP {
	private String uniqueId;
	private PairingManager pm;
	private InetAddress address;

	public static final int PORT = 47984;
	public static final int CONNECTION_TIMEOUT = 3000;
	public static final int READ_TIMEOUT = 5000;
	
	private static boolean verbose = false;

	public String baseUrl;
	
	private OkHttpClient httpClient = new OkHttpClient();
	private OkHttpClient httpClientWithReadTimeout;
		
	private TrustManager[] trustAllCerts;
	private KeyManager[] ourKeyman;
	
	private void initializeHttpState(final LimelightCryptoProvider cryptoProvider) {
		trustAllCerts = new TrustManager[] { 
				new X509TrustManager() {
					public X509Certificate[] getAcceptedIssuers() { 
						return new X509Certificate[0]; 
					}
					public void checkClientTrusted(X509Certificate[] certs, String authType) {}
					public void checkServerTrusted(X509Certificate[] certs, String authType) {}
				}};

		ourKeyman = new KeyManager[] {
				new X509KeyManager() {
					public String chooseClientAlias(String[] keyTypes,
							Principal[] issuers, Socket socket) { return "Limelight-RSA"; }
					public String chooseServerAlias(String keyType, Principal[] issuers,
							Socket socket) { return null; }
					public X509Certificate[] getCertificateChain(String alias) {
						return new X509Certificate[] {cryptoProvider.getClientCertificate()};
					}
					public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
					public PrivateKey getPrivateKey(String alias) {
						return cryptoProvider.getClientPrivateKey();
					}
					public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
				}
		};

		// Ignore differences between given hostname and certificate hostname
		HostnameVerifier hv = new HostnameVerifier() {
			public boolean verify(String hostname, SSLSession session) { return true; }
		};
		
		httpClient.setHostnameVerifier(hv);
		httpClient.setConnectTimeout(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);
		
		httpClientWithReadTimeout = httpClient.clone();
		httpClientWithReadTimeout.setReadTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS);
	}
	
	public NvHTTP(InetAddress host, String uniqueId, String deviceName, LimelightCryptoProvider cryptoProvider) {
		this.uniqueId = uniqueId;
		this.address = host;
		
		String safeAddress;
		if (host instanceof Inet6Address) {
			// RFC2732-formatted IPv6 address for use in URL
			safeAddress = "["+host.getHostAddress()+"]";
		}
		else {
			safeAddress = host.getHostAddress();
		}
		
		initializeHttpState(cryptoProvider);
		
		this.baseUrl = "https://" + safeAddress + ":" + PORT;
		this.pm = new PairingManager(this, cryptoProvider);
	}
	
	static String getXmlString(Reader r, String tagname) throws XmlPullParserException, IOException {
		XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
		factory.setNamespaceAware(true);
		XmlPullParser xpp = factory.newPullParser();

		xpp.setInput(r);
		int eventType = xpp.getEventType();
		Stack<String> currentTag = new Stack<String>();
		
		while (eventType != XmlPullParser.END_DOCUMENT) {
			switch (eventType) {
			case (XmlPullParser.START_TAG):
				if (xpp.getName().equals("root")) {
					verifyResponseStatus(xpp);
				}
				currentTag.push(xpp.getName());
				break;
			case (XmlPullParser.END_TAG):
				currentTag.pop();
				break;
			case (XmlPullParser.TEXT):
				if (currentTag.peek().equals(tagname)) {
					return xpp.getText();
				}
				break;
			}
			eventType = xpp.next();
		}

		return null;
	}

	static String getXmlString(String str, String tagname) throws XmlPullParserException, IOException {
		return getXmlString(new StringReader(str), tagname);
	}
	
	static String getXmlString(InputStream in, String tagname) throws XmlPullParserException, IOException {
		return getXmlString(new InputStreamReader(in), tagname);
	}
	
	private static void verifyResponseStatus(XmlPullParser xpp) throws GfeHttpResponseException {
		int statusCode = Integer.parseInt(xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_code"));
		if (statusCode != 200) {
			throw new GfeHttpResponseException(statusCode, xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_message"));
		}
	}
	
	public String getServerInfo(String uniqueId) throws MalformedURLException, IOException {
		return openHttpConnectionToString(baseUrl + "/serverinfo?uniqueid=" + uniqueId, true);
	}
	
	public ComputerDetails getComputerDetails() throws MalformedURLException, IOException, XmlPullParserException {
		ComputerDetails details = new ComputerDetails();
		String serverInfo = getServerInfo(uniqueId);
		
		details.name = getXmlString(serverInfo, "hostname").trim();
		details.uuid = UUID.fromString(getXmlString(serverInfo, "uniqueid").trim());
		details.macAddress = getXmlString(serverInfo, "mac").trim();

		// If there's no LocalIP field, use the address we hit the server on
		String localIpStr = getXmlString(serverInfo, "LocalIP");
		if (localIpStr == null) {
			localIpStr = address.getHostAddress();
		}
		
		// If there's no ExternalIP field, use the address we hit the server on
		String externalIpStr = getXmlString(serverInfo, "ExternalIP");
		if (externalIpStr == null) {
			externalIpStr = address.getHostAddress();
		}
		
		details.localIp = InetAddress.getByName(localIpStr.trim());
		details.remoteIp = InetAddress.getByName(externalIpStr.trim());
		
		try {
			details.pairState = Integer.parseInt(getXmlString(serverInfo, "PairStatus").trim()) == 1 ?
					PairState.PAIRED : PairState.NOT_PAIRED;
		} catch (NumberFormatException e) {
			details.pairState = PairState.FAILED;
		}
		
		try {
			details.runningGameId = Integer.parseInt(getXmlString(serverInfo, "currentgame").trim());
		} catch (NumberFormatException e) {
			details.runningGameId = 0;
		}
		
		// We could reach it so it's online
		details.state = ComputerDetails.State.ONLINE;
		
		return details;
	}
	
	// This hack is Android-specific but we do it on all platforms
	// because it doesn't really matter
	private void performAndroidTlsHack(OkHttpClient client) {
		// Doing this each time we create a socket is required
		// to avoid the SSLv3 fallback that causes connection failures
		try {
			SSLContext sc = SSLContext.getInstance("TLSv1");
			sc.init(ourKeyman, trustAllCerts, new SecureRandom());
			
			client.setSslSocketFactory(sc.getSocketFactory());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// Read timeout should be enabled for any HTTP query that requires no outside action
	// on the GFE server. Examples of queries that DO require outside action are launch, resume, and quit.
	// The initial pair query does require outside action (user entering a PIN) but subsequent pairing
	// queries do not.
	private ResponseBody openHttpConnection(String url, boolean enableReadTimeout) throws IOException {
		Request request = new Request.Builder().url(url).build();
		Response response;
		
		if (enableReadTimeout) {
			performAndroidTlsHack(httpClientWithReadTimeout);
			response = httpClientWithReadTimeout.newCall(request).execute();
		}
		else {
			performAndroidTlsHack(httpClient);
			response = httpClient.newCall(request).execute();
		}
		
		if (response.isSuccessful()) {
			return response.body();
		}
		else if (response.code() == 404) {
			throw new FileNotFoundException(url);
		}
		else {
			throw new IOException("HTTP request failed: "+response.code());
		}
	}
	
	String openHttpConnectionToString(String url, boolean enableReadTimeout) throws MalformedURLException, IOException {
		if (verbose) {
			LimeLog.info("Requesting URL: "+url);
		}
		
		ResponseBody resp;
		try {
			resp = openHttpConnection(url, enableReadTimeout);
		} catch (IOException e) {
			if (verbose) {
				e.printStackTrace();
			}
			throw e;
		}
		
		Scanner s = new Scanner(resp.byteStream());
		
		String str = "";
		while (s.hasNext()) {
			str += s.next() + " ";
		}
		
		s.close();
		resp.close();
		
		if (verbose) {
			LimeLog.info(url+" -> "+str);
		}
		
		return str;
	}

	public String getServerVersion(String serverInfo) throws XmlPullParserException, IOException {
		return getXmlString(serverInfo, "appversion");
	}

	public PairingManager.PairState getPairState() throws IOException, XmlPullParserException {
		return pm.getPairState(getServerInfo(uniqueId));
	}

	public PairingManager.PairState getPairState(String serverInfo) throws IOException, XmlPullParserException {
		return pm.getPairState(serverInfo);
	}

	public int getCurrentGame(String serverInfo) throws IOException, XmlPullParserException {
		String game = getXmlString(serverInfo, "currentgame");
		return Integer.parseInt(game);
	}
	
	public NvApp getAppById(int appId) throws IOException, XmlPullParserException {
		LinkedList<NvApp> appList = getAppList();
		for (NvApp appFromList : appList) {
			if (appFromList.getAppId() == appId) {
				return appFromList;
			}
		}
		return null;
	}
	
	/* NOTE: Only use this function if you know what you're doing.
	 * It's totally valid to have two apps named the same thing,
	 * or even nothing at all! Look apps up by ID if at all possible
	 * using the above function */
	public NvApp getAppByName(String appName) throws IOException, XmlPullParserException {
		LinkedList<NvApp> appList = getAppList();
		for (NvApp appFromList : appList) {
			if (appFromList.getAppName().equalsIgnoreCase(appName)) {
				return appFromList;
			}
		}
		return null;
	}
	
	public PairingManager.PairState pair(String pin) throws Exception {
		return pm.pair(uniqueId, pin);
	}
	
	public static LinkedList<NvApp> getAppListByReader(Reader r) throws XmlPullParserException, IOException {
		XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
		factory.setNamespaceAware(true);
		XmlPullParser xpp = factory.newPullParser();

		xpp.setInput(r);
		int eventType = xpp.getEventType();
		LinkedList<NvApp> appList = new LinkedList<NvApp>();
		Stack<String> currentTag = new Stack<String>();
		boolean rootTerminated = false;

		while (eventType != XmlPullParser.END_DOCUMENT) {
			switch (eventType) {
			case (XmlPullParser.START_TAG):
				if (xpp.getName().equals("root")) {
					verifyResponseStatus(xpp);
				}
				currentTag.push(xpp.getName());
				if (xpp.getName().equals("App")) {
					appList.addLast(new NvApp());
				}
				break;
			case (XmlPullParser.END_TAG):
				currentTag.pop();
				if (xpp.getName().equals("root")) {
					rootTerminated = true;
				}
				break;
			case (XmlPullParser.TEXT):
				NvApp app = appList.getLast();
				if (currentTag.peek().equals("AppTitle")) {
					app.setAppName(xpp.getText().trim());
				} else if (currentTag.peek().equals("ID")) {
					app.setAppId(xpp.getText().trim());
				} else if (currentTag.peek().equals("IsRunning")) {
					app.setIsRunning(xpp.getText().trim());
				}
				break;
			}
			eventType = xpp.next();
		}
		
		// Throw a malformed XML exception if we've not seen the root tag ended
		if (!rootTerminated) {
			throw new XmlPullParserException("Malformed XML: Root tag was not terminated");
		}
		
		// Ensure that all apps in the list are initialized
		ListIterator<NvApp> i = appList.listIterator();
		while (i.hasNext()) {
			NvApp app = i.next();
			
			// Remove uninitialized apps
			if (!app.isInitialized()) {
				LimeLog.warning("GFE returned incomplete app: "+app.getAppId()+" "+app.getAppName()+" "+app.getIsRunning());
				i.remove();
			}
		}
		
		return appList;
	}
	
	public String getAppListRaw() throws MalformedURLException, IOException {
		return openHttpConnectionToString(baseUrl + "/applist?uniqueid=" + uniqueId, true);
	}
	
	public LinkedList<NvApp> getAppList() throws GfeHttpResponseException, IOException, XmlPullParserException {
		if (verbose) {
			// Use the raw function so the app list is printed
			return getAppListByReader(new StringReader(getAppListRaw()));
		}
		else {
			ResponseBody resp = openHttpConnection(baseUrl + "/applist?uniqueid=" + uniqueId, true);
			LinkedList<NvApp> appList = getAppListByReader(new InputStreamReader(resp.byteStream()));
			resp.close();
			return appList;
		}
	}
	
	public void unpair() throws IOException {
		openHttpConnectionToString(baseUrl + "/unpair?uniqueid=" + uniqueId, true);
	}
	
	public InputStream getBoxArt(NvApp app) throws IOException {
		ResponseBody resp = openHttpConnection(baseUrl + "/appasset?uniqueid=" + uniqueId +
				"&appid=" + app.getAppId() + "&AssetType=2&AssetIdx=0", true);
		return resp.byteStream();
	}

	final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
	private static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
	
	public int launchApp(ConnectionContext context, int appId) throws IOException, XmlPullParserException {
		String xmlStr = openHttpConnectionToString(baseUrl +
			"/launch?uniqueid=" + uniqueId +
			"&appid=" + appId +
			"&mode=" + context.streamConfig.getWidth() + "x" + context.streamConfig.getHeight() + "x" + context.streamConfig.getRefreshRate() +
			"&additionalStates=1&sops=" + (context.streamConfig.getSops() ? 1 : 0) +
			"&rikey="+bytesToHex(context.riKey.getEncoded()) +
			"&rikeyid="+context.riKeyId +
			"&localAudioPlayMode=" + (context.streamConfig.getPlayLocalAudio() ? 1 : 0), false);
		String gameSession = getXmlString(xmlStr, "gamesession");
		return Integer.parseInt(gameSession);
	}
	
	public boolean resumeApp(ConnectionContext context) throws IOException, XmlPullParserException {
		String xmlStr = openHttpConnectionToString(baseUrl + "/resume?uniqueid=" + uniqueId +
				"&rikey="+bytesToHex(context.riKey.getEncoded()) +
				"&rikeyid="+context.riKeyId, false);
		String resume = getXmlString(xmlStr, "resume");
		return Integer.parseInt(resume) != 0;
	}
	
	public boolean quitApp() throws IOException, XmlPullParserException {
		String xmlStr = openHttpConnectionToString(baseUrl + "/cancel?uniqueid=" + uniqueId, false);
		String cancel = getXmlString(xmlStr, "cancel");
		return Integer.parseInt(cancel) != 0;
	}
}
