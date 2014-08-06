package org.alfresco.consulting.indexer.entities;

import java.util.List;
import java.util.Set;

public class NodeBatchLoadEntity extends org.alfresco.repo.domain.node.ibatis.NodeBatchLoadEntity {
  private Long minId;
  private Long maxId;
  private String uuid;
  private Set<String> allowedTypes;
  private Set<String> excludedNameExtension;

  //These input values will be set on all returned NodeEntity objects returned by iBatis mappers
  private String storeProtocol;
  private String storeIdentifier;
  
  public Set<String> getAllowedTypes() {
    return allowedTypes;
  }

  public Long getMinId() {
    return minId;
  }

  public void setMinId(Long minId) {
    this.minId = minId;
  }

  public Long getMaxId() {
    return maxId;
  }

  public void setMaxId(Long maxId) {
    this.maxId = maxId;
  }

  public void setAllowedTypes(Set<String> allowedTypes) {
    this.allowedTypes = allowedTypes;
  }

  public String getStoreProtocol() {
    return storeProtocol;
  }

  public void setStoreProtocol(String storeProtocol) {
    this.storeProtocol = storeProtocol;
  }

  public String getStoreIdentifier() {
    return storeIdentifier;
  }

  public void setStoreIdentifier(String storeIdentifier) {
    this.storeIdentifier = storeIdentifier;
  }
  
  public void setUuid(String uuid){
      this.uuid=uuid;
  }
  
  public String getUuid(){
      return this.uuid;
  }
  
  public void setExcludedNameExtension(Set<String> excludedNameExtension){
      this.excludedNameExtension= excludedNameExtension;
  }
  
  public Set<String> getExcludedNameExtension(){
      return this.excludedNameExtension;
  }
}
