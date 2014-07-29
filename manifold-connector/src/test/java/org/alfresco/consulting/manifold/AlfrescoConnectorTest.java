package org.alfresco.consulting.manifold;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.alfresco.consulting.indexer.client.AlfrescoClient;
import org.alfresco.consulting.indexer.client.AlfrescoResponse;
import org.apache.manifoldcf.agents.interfaces.RepositoryDocument;
import org.apache.manifoldcf.core.interfaces.ManifoldCFException;
import org.apache.manifoldcf.crawler.interfaces.DocumentSpecification;
import org.apache.manifoldcf.crawler.interfaces.IProcessActivity;
import org.apache.manifoldcf.crawler.interfaces.ISeedingActivity;
import org.apache.manifoldcf.crawler.system.SeedingActivity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.google.gson.Gson;

@RunWith(MockitoJUnitRunner.class)
public class AlfrescoConnectorTest {
  private final Gson gson = new Gson();

  @Mock
  private AlfrescoClient client;
  private AlfrescoConnector connector;

  @Before
  public void setup() throws Exception {
    connector = new AlfrescoConnector();
    connector.setClient(client);

    when(client.fetchNodes(anyInt(), anyInt()))
            .thenReturn(new AlfrescoResponse(
                    0, 0, "", "", Collections.<Map<String, Object>>emptyList()));
  }

  @Test
  public void whenAddingSeedDocumentTheAlfrescoClientShouldBeUsed() throws Exception {
    SeedingActivity activities = mock(SeedingActivity.class);
    DocumentSpecification spec = new DocumentSpecification();
    long seedTime = 1;
    int jobMode = 0;
    String lastSeedVersion = "0|0";

    connector.addSeedDocuments(activities, spec, lastSeedVersion, seedTime, jobMode);

    verify(client).fetchNodes(anyInt(), anyInt());
  }

    @Test
    public void addSeedDocumentsWithOnlyAclChangeSetsShouldRetreiveAllChanges() throws Exception {
        when(client.fetchNodes(anyInt(), anyInt()))
                .thenReturn(new AlfrescoResponse(0L, 1L))
                .thenReturn(new AlfrescoResponse(0L, 2L))
                .thenReturn(new AlfrescoResponse(0L, 2L));
        String lastSeedVersion = connector.addSeedDocuments(mock(ISeedingActivity.class),
                new DocumentSpecification(), "0|0", 0L, 0);
        assertEquals("0|2", lastSeedVersion);
    }

  @Test
  public void whenTheClientIsCalledItShouldUseThePreviouslySentLastTransactionId() throws
          Exception {
    long firstTransactionId = 0;
    long lastTransactionId = 5;
    long firstAclChangesetId = 0;
    long lastAclChangesetId = 5;

    when(client.fetchNodes(anyInt(), anyInt()))
            .thenReturn(new AlfrescoResponse(
                    lastTransactionId, lastAclChangesetId));

    connector.addSeedDocuments(mock(SeedingActivity.class),
            new DocumentSpecification(), "0|0", 0L, 0);
    verify(client, times(1)).fetchNodes(eq(firstTransactionId), eq(firstAclChangesetId));

    verify(client, times(1)).fetchNodes(eq(lastTransactionId), eq(lastAclChangesetId));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void whenADocumentIsReturnedItShouldBeAddedToManifold() throws Exception {
    TestDocument testDocument = new TestDocument();
    when(client.fetchNodes(anyInt(), anyInt()))
            .thenReturn(new AlfrescoResponse(0, 0, "", "",
                    Arrays.<Map<String, Object>>asList(testDocument)));

    SeedingActivity seedingActivity = mock(SeedingActivity.class);
    connector.addSeedDocuments(seedingActivity, new DocumentSpecification(), "0|0", 0L, 0);

    String json = gson.toJson(testDocument);
    verify(seedingActivity).addSeedDocument(eq(json));
  }

  @Test
  public void whenProcessingDocumentsNodeRefsAreUsedAsDocumentURI() throws Exception {
    TestDocument testDocument = new TestDocument();
    String json = gson.toJson(testDocument);
    IProcessActivity activities = mock(IProcessActivity.class);
    connector.processDocuments(new String[]{json}, null, activities, null, null, 0);

    ArgumentCaptor<RepositoryDocument> rd = ArgumentCaptor.forClass(RepositoryDocument.class);
    verify(activities)
            .ingestDocumentWithException(eq(TestDocument.uuid), anyString(),
                    eq(TestDocument.uuid), rd.capture());

    Iterator<String> i = rd.getValue().getFields();
    while(i.hasNext()) {
      String fieldName = i.next();
      Object value1 = rd.getValue().getField(fieldName)[0];
      Object value2 = testDocument.getRepositoryDocument().getField(fieldName)[0];
      assert value1.equals(value2);
    }
  }

  @Test
  public void whenProcessingDeletionShouldBeRegisteredAsDeletions() throws Exception {
    TestDocument testDocument = new TestDocument();
    testDocument.setDeleted(true);

    String json = gson.toJson(testDocument);
    IProcessActivity activities = mock(IProcessActivity.class);
    connector.processDocuments(new String[]{json}, null, activities, null, null, 0);

    verify(activities).deleteDocument(eq(TestDocument.uuid));
    verify(activities, never()).ingestDocumentWithException(eq(TestDocument.uuid), anyString(), anyString(),
            any(RepositoryDocument.class));

  }

  @SuppressWarnings("serial")
  private class TestDocument extends HashMap<String, Object> {
    static final String uuid = "abc123";
    static final String type = "cm:content";
    static final boolean deleted = false;
    static final String storeId = "SpacesStore";
    static final String storeProtocol = "workspace";

    public TestDocument() {
      super();
      put("uuid", uuid);
      put("type", type);
      put("deleted", deleted);
      put("store_id", storeId);
      put("store_protocol", storeProtocol);
    }

    public void setDeleted(boolean deleted) {
      put("deleted", deleted);
    }

    public RepositoryDocument getRepositoryDocument() throws ManifoldCFException {
      RepositoryDocument rd = new RepositoryDocument();
      rd.setFileName(uuid);
      for(String property : keySet()) {
        rd.addField(property,get(property).toString());
      }
      return rd;
    }
  }
}
