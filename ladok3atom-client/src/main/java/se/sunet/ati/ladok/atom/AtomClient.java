package se.sunet.ati.ladok.atom;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.net.ssl.KeyManagerFactory;

import org.apache.abdera.Abdera;
import org.apache.abdera.model.Document;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.apache.abdera.model.Link;
import org.apache.abdera.protocol.Response.ResponseType;
import org.apache.abdera.protocol.client.AbderaClient;
import org.apache.abdera.protocol.client.ClientResponse;
import org.apache.abdera.protocol.client.util.ClientAuthSSLProtocolSocketFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class AtomClient {

	public static final String FEED_ENTRY_SEPARATOR = ";";
	private static final String LINK_NAME_PREVIOUS_ARCHIVE = "prev-archive";
	private static final String LINK_NAME_NEXT_ARCHIVE = "next-archive";
	public static String TOO_MANY_EVENTS_REQUESTED = "Too many events requested :-(";
	private String feedBase = null;
	private String lastFeed = null;
	private String certificateFile = null;
	private String certificatePwd = null;
	private Log log = LogFactory.getLog(this.getClass());
	private static int MAX_ENTRIES_PER_RUN = 100;

	private Properties properties;

	private boolean useCert = false;


	public AtomClient() throws Exception {
		properties = new Properties();
		try {
			InputStream in = this.getClass().getClassLoader().getResourceAsStream("atomclient.properties");
			if (in == null) {
				throw new Exception("Unable to find atomclient.properties (see atomclient.properties.sample)");
			}
			
			properties.load(in);
			
			if ((feedBase = properties.getProperty("feedbase")) == null) {
				throw new Exception("Missing property \"feedbase\"");
			}

			lastFeed = properties.getProperty("lastFeed");
			
			if (feedBase.startsWith("https")) {
				useCert = true;
			}
			
			// Check certificate and password.
			if (useCert) {
				
				certificateFile = properties.getProperty("certificateFile");
				if (certificateFile == null || certificateFile.equals("")) {
					throw new Exception("Missing property \"certificateFile\".");					
				}
				
				if (this.getClass().getClassLoader().getResourceAsStream(certificateFile) == null) {
					throw new Exception("Property \"certificateFile\" have no corresponding resource.");
				}
				
				log.info("certificate=" + certificateFile);
				
				certificatePwd = properties.getProperty("certificatePwd");
				if (certificatePwd == null || certificatePwd.equals("")) {
					throw new Exception("Missing property \"certificatePwd\".");					
				}
				
			}
			

		}
		catch (IOException e) {
			log.error("Unable to read atomclient.properties");
			throw e;
		}
	}

	/**
	 * Hämtar en klient för att hämta feeds.
	 * 
	 * @return En klient som kan returnera feeds.
	 * @throws Exception Om någonting i certifikatshanteringen fungerar.
 	 */
	private AbderaClient getClient() throws Exception {
		log.info("useCert=" + useCert);
		Abdera abdera = new Abdera();
		AbderaClient client = new AbderaClient(abdera);
		KeyStore keystore;
		try {
			if (useCert) {
				keystore = KeyStore.getInstance("PKCS12");
				keystore.load(this.getClass().getClassLoader().getResourceAsStream(certificateFile), certificatePwd.toCharArray());
				ClientAuthSSLProtocolSocketFactory factory = new ClientAuthSSLProtocolSocketFactory(keystore, certificatePwd, "TLS",KeyManagerFactory.getDefaultAlgorithm(),null);
				AbderaClient.registerFactory(factory, 443);
			}
			return(client);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new Exception(e.getMessage());
		}
	}

	/**
	 * Hämtar ett feed-objekt från en given URL.
	 * 
	 * @param url URL för den feed som efterfrågas.
	 * @return Efterfrågad feed.
	 */
	private Feed getFeed(String url) {
		log.info("Fetching feed: " + url);
		
		Feed f = null;
		
		if (url != null) {
			try {
				ClientResponse resp = getClient().get(url);

				if (resp.getType() == ResponseType.SUCCESS) {
					Document<Feed> doc = resp.getDocument();
					System.out.println(doc.getRoot().getTitle());
					f = doc.getRoot();
				} else {

					// Only accept success or client error (logical error).
					if (resp.getType() != ResponseType.CLIENT_ERROR)
						throw new UnexpectedClientResponseException(resp
								.getType().toString());

				}
			} catch (Exception e) {
				e.printStackTrace();
				log.error(e + " :: " + url);
			}
		}
		
		return f;
	
	}
	
	/**
	 * Hämtar det första arkivet med händelser i hela systemet.
	 * 
	 * @param f Det arkiv som man man utgår från.
	 * @return Det första arkivet i kedjan av händelsearkiv.
	 */
	private Feed findFirstFeed(Feed f) {

		Feed first = f;		
		
		log.info("Finding first feed from: " + f.getId());
		Feed previous = getFeed(getPreviousUrl(f));

		while (previous != null) {
			first = previous;
			previous = getFeed(getPreviousUrl(previous));
		}
		
		return first;		
	}
	
	/**
	 * Hittar den första händelsen i hela arkivet och returnerar det
	 * som en sammanslagning tillsammans med identiferare för det arkiv
	 * som händelsen är dokumenterad i.
	 * 
	 * @param f Det arkiv som är utgångspunkten.
	 * @return Idenfifeiraren för den första händelsen i en sammanslagning med identifieraren för hemvistarkivet.
	 */
	private String findFirstFeedIdAndFirstEntryId(Feed f) {
		
		log.info("Finding first feed and entry from: " + f.getBaseUri());
		
		Feed firstFeed = findFirstFeed(f);
		if (firstFeed == null) {
			return null;
		}
		List<Entry> entries = firstFeed.getEntries();
		if (entries == null) {
			return null;
		}
		Entry firstEntry = entries.get(entries.size() - 1);
		
		return firstFeed.getBaseUri().toString().substring(
				firstFeed.getBaseUri().toString().lastIndexOf("/") + 1, 
				firstFeed.getBaseUri().toString().length()) + 
			FEED_ENTRY_SEPARATOR + firstEntry.getId().toString();
	}
	
	/**
	 * Hämta händelser efter den senast lästa men hämtar aldrig fler än 
	 * MAX_ENTRIES_PER_RUN händelser per anrop.
	 * 
	 * Om ingen utgångspunkt för frågan är definierad försöker man utgå från 
	 * det senaste akrivet defineirat i klientens egenskapsfil.
	 * 
	 * @param feedIdAndLastEntryId Identifierare för den senast lästa händelsen inklusive referens till identifieraren för händelsens hemvistakriv.
	 * @return En lista av händelser.
	 * @throws Exception Om det inte finns någon riktig utgångspunkt för frågan.
	 */
	public List<Entry> getEntries(String feedIdAndLastEntryId) throws Exception {
		log.info("Attempting to get all events starting from  " + feedIdAndLastEntryId);

		String[] parsed = null;
		String firstId = null;
		
		if (feedIdAndLastEntryId != null && !feedIdAndLastEntryId.equals("0")) {
			parsed = feedIdAndLastEntryId.split(FEED_ENTRY_SEPARATOR);
		} else {
			firstId = findFirstFeedIdAndFirstEntryId(getFeed(lastFeed));
			if (firstId != null) 
				parsed = firstId.split(FEED_ENTRY_SEPARATOR);
		}
		
		if (parsed == null)
			throw new Exception("Ingen riktig utgångspunkt hittades för frågan.");
		
		String feedId = parsed[0];
		String entryId = parsed[1];
		
		return getEntries(feedId, (firstId == null ? entryId : null));
	}


	/**
	 * Vänder på alla händelseobjekt i ett arkiv så att de kommer i 
	 * fallande kronologisk ordning.
	 * 
	 * @param f Arkiv som innehåller de händelser som man vill vända på.
	 * @return En lista av händelser i kronologisk fallande ordning.
	 */
	private List<Entry> getSortedEntriesFromFeed(Feed f) {
		List<Entry> entries = new ArrayList<Entry>(f.getEntries());
		Collections.reverse(entries);
		return entries;
	}
	
	/**
	 * Filtrerar bort de händelser som redan har lästs.
	 * 
	 * @param unfilteredEntries Lista av händelser som innehåller både lästa och oläsa händelser.
	 * @param lastReadEntryId Det senast lästa entryt.
	 * @return En lista av olästa händelser.
	 */
	private List<Entry> filterOlderEntries(List<Entry> unfilteredEntries, String lastReadEntryId) {

		int indexOfLastReadEntry = 0;
		for (Entry entry : unfilteredEntries) {
			indexOfLastReadEntry++;
			if (entry.getId().toString().equals(lastReadEntryId)) {
				break;
			}
		}
		
		// We need to handle the first event to, passed as null.
		if (lastReadEntryId == null)
			indexOfLastReadEntry = 0;
		
		List<Entry> result = unfilteredEntries.subList(indexOfLastReadEntry, unfilteredEntries.size());
		return result;
	}
	
	/**
	 * Hämtar olästa entries från senast lästa entry tillsammans med entryts käll-feed. 
	 * Antalet entries som returneras baseras på MAX_ENTRIES_PER_RUN.
	 * 
	 * @param feedId Identifierarer för feed som senast lästa entry finns i.
	 * @param lastReadEntryId Identifierare för senast lästa entry.
	 * @return En lista av olästa entries.
	 */
	private List<Entry> getEntries(String feedId, String lastReadEntryId) {
		
		log.info("Attempting to get max " + MAX_ENTRIES_PER_RUN + " events from latest feed " + feedId + " and up.");
		Feed f = getFeed(feedBase + feedId);
		List<Entry> entries = new ArrayList<Entry>();
		if (f != null) {
			entries.addAll(filterOlderEntries(getSortedEntriesFromFeed(f), lastReadEntryId));
			while (f != null && getNextUrl(f) != null && entries.size() < MAX_ENTRIES_PER_RUN) {
				f = getFeed(getNextUrl(f));
				if (f != null) {
					entries.addAll(getSortedEntriesFromFeed(f));
				}
			}
			log.info("Started from " +  lastReadEntryId + " in feed " + feedId + " and found " + entries.size()
					+ " entries");
		}
		return entries;
	}

	/**
	 * Hjälpmetod för at hämta ut länkars värde ur en feed.
	 * 
	 * @param f Den feed man vill extrahera länkar från.
	 * @param linkname Namnet på länken.
	 * @return URL för efterfrågad länk.
	 */
	private String getLinkHref(Feed f, String linkname) {
		String retval = null;
		for (Link link : f.getLinks()) {
			if (linkname.equalsIgnoreCase(link.getRel())) {
				retval = link.getAttributeValue("href");
				retval = retval.replaceAll("http://mit[0-9]+-ladok3.its.umu.se:[0-9]+", "https://api.mit.ladok.se");
				break;
			}
		}
		return retval;		
	}
	
	/**
	 * Hämtar URL till nästa arkiv i ordningen.
	 * 
	 * @param f Det arkiv man vill basera frågan på.
	 * @return URL till nästa arkiv.
	 */
	private String getNextUrl(Feed f) {
		return getLinkHref(f, LINK_NAME_NEXT_ARCHIVE);
	}
	
	/**
	 * Hämtar URL till föregående arkiv i ordningen.
	 * 
	 * @param f Det arkiv man vill basera frågan på.
	 * @return URL till föregående arkiv.
	 */
	private String getPreviousUrl(Feed f) {
		String linkHref = getLinkHref(f, LINK_NAME_PREVIOUS_ARCHIVE);
		log.info("getPreviousUrl linkHref: " + linkHref);
		return linkHref;
	}

}
