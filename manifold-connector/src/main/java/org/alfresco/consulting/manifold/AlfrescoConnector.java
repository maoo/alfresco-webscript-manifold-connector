package org.alfresco.consulting.manifold;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringTokenizer;

import org.alfresco.consulting.indexer.client.AlfrescoClient;
import org.alfresco.consulting.indexer.client.AlfrescoDownException;
import org.alfresco.consulting.indexer.client.AlfrescoResponse;
import org.alfresco.consulting.indexer.client.WebScriptsAlfrescoClient;
import org.apache.manifoldcf.agents.interfaces.RepositoryDocument;
import org.apache.manifoldcf.agents.interfaces.ServiceInterruption;
import org.apache.manifoldcf.core.interfaces.ConfigParams;
import org.apache.manifoldcf.core.interfaces.IHTTPOutput;
import org.apache.manifoldcf.core.interfaces.IPostParameters;
import org.apache.manifoldcf.core.interfaces.IThreadContext;
import org.apache.manifoldcf.core.interfaces.ManifoldCFException;
import org.apache.manifoldcf.core.interfaces.Specification;
import org.apache.manifoldcf.crawler.connectors.BaseRepositoryConnector;
import org.apache.manifoldcf.crawler.interfaces.DocumentSpecification;
import org.apache.manifoldcf.crawler.interfaces.IProcessActivity;
import org.apache.manifoldcf.crawler.interfaces.ISeedingActivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

public class AlfrescoConnector extends BaseRepositoryConnector {
  private static final Logger logger = LoggerFactory.getLogger(AlfrescoConnector.class);
  private static final String ACTIVITY_FETCH = "fetch document";
  private static final String[] activitiesList = new String[]{ACTIVITY_FETCH};
  private AlfrescoClient alfrescoClient;
  private final Gson gson = new Gson();
  private Boolean enableDocumentProcessing = Boolean.TRUE;

  @Override
  public int getConnectorModel() {
    return MODEL_ALL; // We will always return all specified documents.
  }

  void setClient(AlfrescoClient client) {
    alfrescoClient = client;
  }

  @Override
  public void connect(ConfigParams config) {
    super.connect(config);

    String protocol = getConfig(config, "protocol", "http");
    String hostname = getConfig(config, "hostname", "localhost");
    String endpoint = getConfig(config, "endpoint", "/alfresco/service");
    String storeProtocol = getConfig(config, "storeprotocol", "workspace");
    String storeId = getConfig(config, "storeid", "SpacesStore");
    String username = getConfig(config, "username", null);
    String password = getConfig(config, "password", null);
    this.enableDocumentProcessing = new Boolean(getConfig(config, "enabledocumentprocessing", "false"));

    alfrescoClient = new WebScriptsAlfrescoClient(protocol, hostname, endpoint,
            storeProtocol, storeId, username, password);
  }

  private static String getConfig(ConfigParams config,
                                  String parameter,
                                  String defaultValue) {
    final String protocol = config.getParameter(parameter);
    if (protocol == null) {
      return defaultValue;
    }
    return protocol;
  }

  @Override
  public String check() throws ManifoldCFException {
    return super.check();
  }

  @Override
  public void disconnect() throws ManifoldCFException {
    super.disconnect();
  }

  @Override
  public String[] getActivitiesList() {
    return activitiesList;
  }

  @Override
  public int getMaxDocumentRequest() {
    return 20;
  }

  @Override
  public String addSeedDocuments(ISeedingActivity activities, Specification spec,
                                              String lastSeedVersion, long seedTime, int jobMode) throws ManifoldCFException, ServiceInterruption {
    try {
      StringTokenizer tokenizer = new StringTokenizer(lastSeedVersion,"|");
      long lastTransactionId = 0;
      long lastAclChangesetId = 0;

      if (tokenizer.countTokens() == 2) {
         lastTransactionId = new Long(tokenizer.nextToken());
         lastAclChangesetId = new Long(tokenizer.nextToken());
      }

      logger.info("Starting from transaction id: {} and acl changeset id: {}", lastTransactionId, lastAclChangesetId);

      long transactionIdsProcessed;
      long aclChangesetsProcessed;
      do {
        final AlfrescoResponse response = alfrescoClient.fetchNodes(lastTransactionId, lastAclChangesetId);
        int count = 0;
        for (Map<String, Object> doc : response.getDocuments()) {
          String json = gson.toJson(doc);
          activities.addSeedDocument(json);
          count++;
        }
        logger.info("Fetched and added {} seed documents", count);

        transactionIdsProcessed = response.getLastTransactionId() - lastTransactionId;
        aclChangesetsProcessed = response.getLastAclChangesetId() - lastAclChangesetId;

        lastTransactionId = response.getLastTransactionId();
        lastAclChangesetId = response.getLastAclChangesetId();

        logger.info("transaction_id={}, acl_changeset_id={}", lastTransactionId, lastAclChangesetId);
      } while (transactionIdsProcessed > 0 || aclChangesetsProcessed > 0);

      logger.info("Recording {} as last transaction id and {} as last changeset id", lastTransactionId, lastAclChangesetId);
      return lastTransactionId + "|" + lastAclChangesetId;
    } catch (AlfrescoDownException e) {
      throw new ManifoldCFException(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void processDocuments(String[] documentIdentifiers, String[] versions,
                               IProcessActivity activities, DocumentSpecification spec,
                               boolean[] scanOnly, int jobMode) throws ManifoldCFException,
          ServiceInterruption {
    for (String doc : documentIdentifiers) {
      Map<String, Object> map = gson.fromJson(doc, Map.class);
      RepositoryDocument rd = new RepositoryDocument();
      String uuid = map.get("uuid").toString();
      rd.setFileName(uuid);
      for (Entry<String, Object> e : map.entrySet()) {
        rd.addField(e.getKey(), e.getValue().toString());
      }

      if ((Boolean) map.get("deleted")) {
        activities.deleteDocument(uuid);
      } else {
        if (this.enableDocumentProcessing) {
          processMetaData(rd,uuid);
        }
        try {
            activities.ingestDocumentWithException(String.valueOf(uuid), "", uuid, rd);
        } catch (IOException e) {
            throw new ManifoldCFException("Caught IOException while ingesting document with uuid: " + uuid);
        }
      }
    }
  }

  private void processMetaData(RepositoryDocument rd, String uuid) throws ManifoldCFException {
    Map<String,Object> properties = alfrescoClient.fetchMetadata(uuid);
    for(String property : properties.keySet()) {
      Object propertyValue = properties.get(property);
      rd.addField(property,propertyValue.toString());
    }
  }

  @Override
  public void outputConfigurationHeader(IThreadContext threadContext,
                                        IHTTPOutput out, Locale locale, ConfigParams parameters,
                                        List<String> tabsArray) throws ManifoldCFException, IOException {
    ConfigurationHandler.outputConfigurationHeader(threadContext, out, locale,
            parameters, tabsArray);
  }

  @Override
  public void outputConfigurationBody(IThreadContext threadContext,
                                      IHTTPOutput out, Locale locale, ConfigParams parameters, String tabName)
          throws ManifoldCFException, IOException {
    ConfigurationHandler.outputConfigurationBody(threadContext, out, locale,
            parameters, tabName);
  }

  @Override
  public String processConfigurationPost(IThreadContext threadContext,
                                         IPostParameters variableContext, Locale locale, ConfigParams parameters)
          throws ManifoldCFException {
    return ConfigurationHandler.processConfigurationPost(threadContext,
            variableContext, locale, parameters);
  }

  @Override
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
                                Locale locale, ConfigParams parameters) throws ManifoldCFException,
          IOException {
    ConfigurationHandler.viewConfiguration(threadContext, out, locale,
            parameters);
  }
}