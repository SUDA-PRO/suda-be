package org.egov.inbox.service;

import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.inbox.repository.ServiceRequestRepository;
import org.egov.inbox.web.model.InboxSearchCriteria;
import org.egov.inbox.web.model.workflow.ProcessInstanceSearchCriteria;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.egov.inbox.util.PTConstants.*;
import static org.egov.inbox.util.PTConstants.LIMIT_PARAM;

@Slf4j
@Service
public class PtInboxFilterService {

    @Value("${egov.user.host}")
    private String userHost;

    @Value("${egov.user.search.path}")
    private String userSearchEndpoint;

    @Value("${egov.searcher.host}")
    private String searcherHost;

    @Value("${egov.searcher.pt.search.path}")
    private String ptInboxSearcherEndpoint;

    @Value("${egov.searcher.pt.search.desc.path}")
    private String ptInboxSearcherDescEndpoint;

    @Value("${egov.searcher.pt.count.path}")
    private String ptInboxSearcherCountEndpoint;

    // Direct property-services host — used for inbox search so that
    // applications with null propertyId (pre-approval) are not filtered out.
    @Value("${egov.pt.service.host}")
    private String ptServiceHost;

    @Value("${egov.pt.service.search.path}")
    private String ptServiceSearchPath;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    /**
     * Fetches acknowledgement IDs directly from property-services/_search.
     * This avoids the egov-searcher SQL which may have a "propertyid IS NOT NULL"
     * condition that would filter out new applications (where propertyId is deferred
     * to post-approval). isInboxSearch=true bypasses mandatory-criteria validation.
     */
    public List<String> fetchAcknowledgementIdsFromSearcher(InboxSearchCriteria criteria, HashMap<String, String> StatusIdNameMap, RequestInfo requestInfo){
        List<String> acknowledgementNumbers = new ArrayList<>();
        HashMap moduleSearchCriteria = criteria.getModuleSearchCriteria();
        ProcessInstanceSearchCriteria processCriteria = criteria.getProcessSearchCriteria();

        Boolean isMobileNumberPresent = moduleSearchCriteria.containsKey(MOBILE_NUMBER_PARAM);
        List<String> userUUIDs = new ArrayList<>();
        if(isMobileNumberPresent) {
            String tenantId = criteria.getTenantId();
            String mobileNumber = String.valueOf(moduleSearchCriteria.get(MOBILE_NUMBER_PARAM));
            userUUIDs = fetchUserUUID(mobileNumber, requestInfo, tenantId);
            if(CollectionUtils.isEmpty(userUUIDs)){
                return new ArrayList<>();
            }
        }

        // Build query params for property-services/_search
        StringBuilder uri = new StringBuilder();
        uri.append(ptServiceHost).append(ptServiceSearchPath);
        uri.append("?tenantId=").append(criteria.getTenantId());
        uri.append("&isInboxSearch=true");

        // creationReason filter
        uri.append("&creationReason=CREATE&creationReason=MUTATION&creationReason=UPDATE");

        // status filter — use workflow status names (INWORKFLOW, ACTIVE, INACTIVE)
        if(!ObjectUtils.isEmpty(processCriteria.getStatus()) && !processCriteria.getStatus().isEmpty()){
            // StatusIdNameMap maps uuid->name; pass status names
            processCriteria.getStatus().forEach(stId -> {
                String stName = StatusIdNameMap.get(stId);
                if(!ObjectUtils.isEmpty(stName)) uri.append("&status=").append(stName);
            });
        }

        if(moduleSearchCriteria.containsKey(PROPERTY_ID_PARAM)){
            uri.append("&propertyIds=").append(moduleSearchCriteria.get(PROPERTY_ID_PARAM));
        }
        if(moduleSearchCriteria.containsKey(PT_APPLICATION_NUMBER_PARAM)){
            uri.append("&acknowledgementIds=").append(moduleSearchCriteria.get(PT_APPLICATION_NUMBER_PARAM));
        }
        if(moduleSearchCriteria.containsKey(LOCALITY_PARAM)){
            uri.append("&locality=").append(moduleSearchCriteria.get(LOCALITY_PARAM));
        }
        if(!CollectionUtils.isEmpty(userUUIDs)){
            userUUIDs.forEach(uuid -> uri.append("&ownerIds=").append(uuid));
        }

        // pagination
        uri.append("&limit=").append(criteria.getLimit());
        uri.append("&offset=").append(criteria.getOffset());

        moduleSearchCriteria.put(LIMIT_PARAM, criteria.getLimit());

        Map<String, Object> searchRequest = new HashMap<>();
        searchRequest.put(REQUESTINFO_PARAM, requestInfo);

        Object result = restTemplate.postForObject(uri.toString(), searchRequest, Map.class);
        if(result != null){
            acknowledgementNumbers = JsonPath.read(result, "$.Properties[*].acknowldgementNumber");
        }
        return acknowledgementNumbers;
    }

    public Integer fetchAcknowledgementIdsCountFromSearcher(InboxSearchCriteria criteria, HashMap<String, String> StatusIdNameMap, RequestInfo requestInfo){
        HashMap moduleSearchCriteria = criteria.getModuleSearchCriteria();
        ProcessInstanceSearchCriteria processCriteria = criteria.getProcessSearchCriteria();

        Boolean isMobileNumberPresent = moduleSearchCriteria.containsKey(MOBILE_NUMBER_PARAM);
        List<String> userUUIDs = new ArrayList<>();
        if(isMobileNumberPresent) {
            String tenantId = criteria.getTenantId();
            String mobileNumber = String.valueOf(moduleSearchCriteria.get(MOBILE_NUMBER_PARAM));
            userUUIDs = fetchUserUUID(mobileNumber, requestInfo, tenantId);
            if(CollectionUtils.isEmpty(userUUIDs)){
                return 0;
            }
        }

        StringBuilder uri = new StringBuilder();
        uri.append(ptServiceHost).append(ptServiceSearchPath);
        uri.append("?tenantId=").append(criteria.getTenantId());
        uri.append("&isInboxSearch=true");
        uri.append("&isRequestForCount=true");
        uri.append("&creationReason=CREATE&creationReason=MUTATION&creationReason=UPDATE");

        if(!ObjectUtils.isEmpty(processCriteria.getStatus()) && !processCriteria.getStatus().isEmpty()){
            processCriteria.getStatus().forEach(stId -> {
                String stName = StatusIdNameMap.get(stId);
                if(!ObjectUtils.isEmpty(stName)) uri.append("&status=").append(stName);
            });
        }

        if(moduleSearchCriteria.containsKey(PROPERTY_ID_PARAM)){
            uri.append("&propertyIds=").append(moduleSearchCriteria.get(PROPERTY_ID_PARAM));
        }
        if(moduleSearchCriteria.containsKey(PT_APPLICATION_NUMBER_PARAM)){
            uri.append("&acknowledgementIds=").append(moduleSearchCriteria.get(PT_APPLICATION_NUMBER_PARAM));
        }
        if(moduleSearchCriteria.containsKey(LOCALITY_PARAM)){
            uri.append("&locality=").append(moduleSearchCriteria.get(LOCALITY_PARAM));
        }
        if(!CollectionUtils.isEmpty(userUUIDs)){
            userUUIDs.forEach(uuid -> uri.append("&ownerIds=").append(uuid));
        }

        Map<String, Object> searchRequest = new HashMap<>();
        searchRequest.put(REQUESTINFO_PARAM, requestInfo);

        Object result = restTemplate.postForObject(uri.toString(), searchRequest, Map.class);
        if(result != null){
            Object countVal = JsonPath.read(result, "$.count");
            if(countVal != null) return ((Number) countVal).intValue();
        }
        return 0;
    }


    private List<String> fetchUserUUID(String mobileNumber, RequestInfo requestInfo, String tenantId) {
        StringBuilder uri = new StringBuilder();
        uri.append(userHost).append(userSearchEndpoint);
        Map<String, Object> userSearchRequest = new HashMap<>();
        userSearchRequest.put("RequestInfo", requestInfo);
        userSearchRequest.put("tenantId", tenantId);
        userSearchRequest.put("userType", "CITIZEN");
        userSearchRequest.put("mobileNumber", mobileNumber);
        List<String> userUuids = new ArrayList<>();
        try {
            Object user = serviceRequestRepository.fetchResult(uri, userSearchRequest);
            if(null != user) {
                //log.info(user.toString());
                userUuids = JsonPath.read(user, "$.user.*.uuid");
            }else {
                log.error("Service returned null while fetching user for mobile number - " + mobileNumber);
            }
        }catch(Exception e) {
            log.error("Exception while fetching user for mobile number - " + mobileNumber);
            log.error("Exception trace: ", e);
        }
        return userUuids;
    }
}
