package org.swasth.hcx.v1;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hcxprotocol.init.HCXIntegrator;
import io.hcxprotocol.utils.Operations;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.swasth.hcx.dto.Request;
import org.swasth.hcx.dto.Response;
import org.swasth.hcx.dto.ResponseError;
import org.swasth.hcx.exception.ClientException;
import org.swasth.hcx.exception.ErrorCodes;
import org.swasth.hcx.exception.ServerException;
import org.swasth.hcx.exception.ServiceUnavailbleException;
import org.swasth.hcx.fhirexamples.OnActionFhirExamples;
import org.swasth.hcx.service.HcxIntegratorService;
import org.swasth.hcx.service.PayerService;
import org.swasth.hcx.service.PostgresService;
import org.swasth.hcx.utils.JSONUtils;
import org.swasth.hcx.utils.OnActionCall;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.swasth.hcx.utils.Constants.*;

public class BaseController {

    @Autowired
    protected OnActionCall onActionCall;
    @Autowired
    protected HcxIntegratorService hcxIntegratorService;
    @Autowired
    private PostgresService postgresService;
    @Autowired
    private PayerService payerService;

    @Autowired
    protected Environment env;
    @Value("${autoresponse}")
    private Boolean autoResponse;

    @Value("${postgres.table.provider-system}")
    private String providerService;

    @Value("${beneficiary.recipient-code}")
    private String mockRecipientCode;

    IParser parser = FhirContext.forR4().newJsonParser().setPrettyPrint(true);

    protected void processAndValidateRequest(String onApiAction, Request request, Map<String, Object> requestBody, String apiAction) throws Exception {
        String mid = UUID.randomUUID().toString();
        String serviceMode = env.getProperty(SERVICE_MODE);
        System.out.println("\n" + "Mode: " + serviceMode + " :: mid: " + mid + " :: Event: " + onApiAction);
        if (StringUtils.equalsIgnoreCase(serviceMode, GATEWAY)) {
            Map<String, String> pay = new HashMap<>();
            System.out.println("payload received " + requestBody);
            pay.put("payload", String.valueOf(requestBody.get("payload")));
            Map<String, Object> output = new HashMap<>();
            Map<String, Object> outputOfOnAction = new HashMap<>();
            System.out.println("create the oncheck payload");
            Bundle bundle = new Bundle();
            Request req = new Request(requestBody, apiAction);
            HCXIntegrator hcxIntegrator = hcxIntegratorService.getHCXIntegrator(req.getRecipientCode());
            if (COVERAGE_ELIGIBILITY_ONCHECK.equalsIgnoreCase(onApiAction)) {
                boolean result = hcxIntegrator.processIncoming(JSONUtils.serialize(pay), Operations.COVERAGE_ELIGIBILITY_ON_CHECK, output);
                if (!result) {
                    System.out.println("Error while processing incoming request: " + output);
                    throw new ClientException("Exception while decrypting incoming request :" + req.getCorrelationId());
                }
                System.out.println("output map after decryption  coverageEligibility" + output.get("fhirPayload"));
                System.out.println("decryption successful");
                //processing the decrypted incoming bundle
                bundle = parser.parseResource(Bundle.class, (String) output.get("fhirPayload"));
                CoverageEligibilityResponse covRes = OnActionFhirExamples.coverageEligibilityResponseExample();
                covRes.setPatient(new Reference("Patient/RVH1003"));
                replaceResourceInBundleEntry(bundle, "https://ig.hcxprotocol.io/v0.7.1/StructureDefinition-CoverageEligibilityResponseBundle.html", CoverageEligibilityRequest.class, new Bundle.BundleEntryComponent().setFullUrl(covRes.getResourceType() + "/" + covRes.getId().toString().replace("#", "")).setResource(covRes));
                System.out.println("bundle reply " + parser.encodeResourceToString(bundle));
                // check for the request exist if exist then update
                updateRecords(req);
                //sending the onaction call
//                onActionCall.sendOnAction(request.getRecipientCode(),(String) output.get("fhirPayload") , Operations.COVERAGE_ELIGIBILITY_ON_CHECK, String.valueOf(requestBody.get("payload")), "response.complete", outputOfOnAction);
            } else if (CLAIM_SUBMIT.equalsIgnoreCase(apiAction)) {
                boolean result = hcxIntegrator.processIncoming(JSONUtils.serialize(pay), Operations.CLAIM_SUBMIT, output);
                if (!result) {
                    validateErrorsAndSendResponse(output, "/claim/on_submit");
                }else{
                    //processing the decrypted incoming bundle
                    bundle = parser.parseResource(Bundle.class, (String) output.get("fhirPayload"));
                    System.out.println("Received URL " + bundle.getMeta().getProfile().get(0).getValue().toString());
                    System.out.println("Received URL " + "https://ig.hcxprotocol.io/v0.7.1/StructureDefinition-ClaimRequestBundle.html".equals(bundle.getMeta().getProfile().get(0).getValue().toString()));
                    if("https://ig.hcxprotocol.io/v0.7.1/StructureDefinition-ClaimRequestBundle.html".equals(bundle.getMeta().getProfile().get(0).getValue().toString())) {
                        ClaimResponse claimRes = OnActionFhirExamples.claimResponseExample();
                        claimRes.setPatient(new Reference("Patient/RVH1003"));
                        replaceResourceInBundleEntry(bundle, "https://ig.hcxprotocol.io/v0.7.1/StructureDefinition-ClaimResponseBundle.html", Claim.class, new Bundle.BundleEntryComponent().setFullUrl(claimRes.getResourceType() + "/" + claimRes.getId().toString().replace("#", "")).setResource(claimRes));
                        System.out.println("bundle reply " + parser.encodeResourceToString(bundle));
                        sendResponse(apiAction, parser.encodeResourceToString(bundle), (String) output.get("fhirPayload"), Operations.CLAIM_ON_SUBMIT, String.valueOf(requestBody.get("payload")), "response.complete", outputOfOnAction);
                    }else{
                        onActionCall.sendOnActionErrorProtocolResponse(output, new ResponseError(ErrorCodes.ERR_INVALID_DOMAIN_PAYLOAD, "Payload does not contain a claim bundle", null), "/claim/on_submit");
                    }
                }
            } else if (PRE_AUTH_SUBMIT.equalsIgnoreCase(apiAction)) {
                boolean result = hcxIntegrator.processIncoming(JSONUtils.serialize(pay), Operations.PRE_AUTH_SUBMIT, output);
                if (!result) {
                    validateErrorsAndSendResponse(output, "/preauth/on_submit");
                }else {
                    System.out.println("output map after decryption preauth " + output);
                    //processing the decrypted incoming bundle
                    bundle = parser.parseResource(Bundle.class, (String) output.get("fhirPayload"));
                    if("https://ig.hcxprotocol.io/v0.7.1/StructureDefinition-ClaimRequestBundle.html".equals(bundle.getMeta().getProfile().get(0).getValue())) {
                        ClaimResponse preAuthRes = OnActionFhirExamples.claimResponseExample();
                        preAuthRes.setPatient(new Reference("Patient/RVH1003"));
                        preAuthRes.setUse(ClaimResponse.Use.PREAUTHORIZATION);
                        replaceResourceInBundleEntry(bundle, "https://ig.hcxprotocol.io/v0.7.1/StructureDefinition-ClaimResponseBundle.html", Claim.class, new Bundle.BundleEntryComponent().setFullUrl(preAuthRes.getResourceType() + "/" + preAuthRes.getId().toString().replace("#", "")).setResource(preAuthRes));
                        sendResponse(apiAction, parser.encodeResourceToString(bundle), (String) output.get("fhirPayload"), Operations.PRE_AUTH_ON_SUBMIT, String.valueOf(requestBody.get("payload")), "response.complete", outputOfOnAction);
                    }else{
                        onActionCall.sendOnActionErrorProtocolResponse(output, new ResponseError(ErrorCodes.ERR_INVALID_DOMAIN_PAYLOAD, "Payload does not contain a claim bundle", null), "/preauth/on_submit");
                    }
                }
            } else if (COMMUNICATION_REQUEST.equalsIgnoreCase(apiAction)) {
                boolean result = hcxIntegrator.processIncoming(JSONUtils.serialize(pay), Operations.COMMUNICATION_REQUEST, output);
                if (!result) {
                    System.out.println("Error while processing incoming request: " + output);
                }
                System.out.println("output map after decryption communication" + output);
                System.out.println("decryption successful");
                String selectQuery = String.format("SELECT otp_verification from %s WHERE action = 'claim' AND correlation_id = '%s'", providerService, request.getCorrelationId());
                ResultSet resultSet = postgresService.executeQuery(selectQuery);
                String otpVerification = "";
                while (resultSet.next()) {
                    otpVerification = resultSet.getString("otp_verification");
                }
                if (StringUtils.equalsIgnoreCase(otpVerification, "successful")) {
                    String query1 = String.format("UPDATE %s SET bank_details = '%s' WHERE correlation_id = '%s'", providerService, "initiated", request.getCorrelationId());
                    postgresService.execute(query1);
                } else if (StringUtils.equalsIgnoreCase(otpVerification, "Pending")) {
                    String query = String.format("UPDATE %s SET otp_verification = '%s' WHERE correlation_id ='%s'", providerService, "initiated", request.getCorrelationId());
                    postgresService.execute(query);
                }
                sendResponse(apiAction, parser.encodeResourceToString(bundle), (String) output.get("fhirPayload"), Operations.COMMUNICATION_ON_REQUEST, String.valueOf(requestBody.get("payload")), "response.complete", outputOfOnAction);
            }
        }
    }

    private void updateRecords(Request req) throws ClientException, SQLException {
        String query = String.format("SELECT * FROM %s WHERE correlation_id='%s'", providerService, req.getCorrelationId());
        ResultSet resultSet = postgresService.executeQuery(query);
        if (!resultSet.next()) {
            throw new ClientException("The corresponding request does not exist in the database");
        }
        String query1 = String.format("UPDATE %s SET status = '%s' WHERE correlation_id = '%s'", providerService, req.getStatus(), req.getCorrelationId());
        postgresService.execute(query1);
    }

    private void sendResponse(String apiAction, String respfhir, String reqFhir, Operations operation, String actionJwe, String onActionStatus, Map<String, Object> output) throws Exception {
        Request request = new Request(Collections.singletonMap("payload", actionJwe), apiAction);
        if (autoResponse || StringUtils.equalsIgnoreCase(request.getRecipientCode(), env.getProperty("mock_payer.participant_code"))) {
            onActionCall.sendOnAction(request.getRecipientCode(), respfhir, operation, actionJwe, onActionStatus, output);
        } else {
            payerService.process(request, reqFhir, respfhir);
            if (request.getAction().equalsIgnoreCase("/v0.7/coverageeligibility/check") && request.getRecipientCode().equalsIgnoreCase(mockRecipientCode)) {
                Thread.sleep(1000);
                onActionCall.sendOnAction(request.getRecipientCode(), respfhir, Operations.COVERAGE_ELIGIBILITY_ON_CHECK, actionJwe, "response.complete", output);
                String updateQuery = String.format("UPDATE %s SET status='%s',updated_on=%d WHERE request_id='%s' RETURNING %s,%s",
                        providerService, "Approved", System.currentTimeMillis(), request.getApiCallId(), "raw_payload", "response_fhir");
                postgresService.execute(updateQuery);
            }
        }
    }

//    public ResponseEntity<Object> processRequest(Map<String, Object> requestBody, String apiAction, String onApiAction, String kafkaTopic) {
//        Response response = new Response();
//        try {
//            Request request = new Request(requestBody, apiAction);
//            setResponseParams(request, response);
//            processAndValidate(onApiAction, request, requestBody, apiAction);
//            System.out.println("http respond sent");
//            return new ResponseEntity<>(response, HttpStatus.ACCEPTED);
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.out.println("error   " + e);
//            return exceptionHandler(response, e);
//        }
//    }

    protected void replaceResourceInBundleEntry(Bundle bundle, String bundleURL, Class matchClass, Bundle.BundleEntryComponent bundleEntry) {
        //updating the meta
        Meta meta = new Meta();
        meta.getProfile().add(new CanonicalType(bundleURL));
        meta.setLastUpdated(new Date());
        bundle.setMeta(meta);
        for (int i = 0; i < bundle.getEntry().size(); i++) {
            System.out.println("in the loop " + i);
            Bundle.BundleEntryComponent par = bundle.getEntry().get(i);
            DomainResource dm = (DomainResource) par.getResource();
            if (dm.getClass() == matchClass) {
                bundle.getEntry().set(i, bundleEntry);
            }
        }
    }

    protected void validateErrorsAndSendResponse(Map<String,Object> output, String url) throws Exception{
        Map<String,Object> errorObj = (Map<String, Object>) output.get("responseObj");
        System.out.println("Error occured during decryption : " + errorObj.get("error"));
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> map = mapper.readValue((String) errorObj.get("error"), Map.class);
        System.out.println("Error map is here \n" + map.get("code") + "\n" + (String) map.get("message"));
        onActionCall.sendOnActionErrorProtocolResponse(output, new ResponseError(ErrorCodes.valueOf(map.get("code")), map.get("message"), null), url);
    }

    protected void setResponseParams(Request request, Response response) {
        response.setCorrelationId(request.getCorrelationId());
        response.setApiCallId(request.getApiCallId());
    }

    protected ResponseEntity<Object> exceptionHandler(Response response, Exception e) {
        e.printStackTrace();
        if (e instanceof ClientException) {
            return new ResponseEntity<>(errorResponse(response, ((ClientException) e).getErrCode(), e), HttpStatus.BAD_REQUEST);
        } else if (e instanceof ServiceUnavailbleException) {
            return new ResponseEntity<>(errorResponse(response, ((ServiceUnavailbleException) e).getErrCode(), e), HttpStatus.SERVICE_UNAVAILABLE);
        } else if (e instanceof ServerException) {
            return new ResponseEntity<>(errorResponse(response, ((ServerException) e).getErrCode(), e), HttpStatus.INTERNAL_SERVER_ERROR);
        } else {
            return new ResponseEntity<>(errorResponse(response, ErrorCodes.INTERNAL_SERVER_ERROR, e), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    protected Response errorResponse(Response response, ErrorCodes code, java.lang.Exception e) {
        ResponseError error = new ResponseError(code, e.getMessage(), e.getCause());
        response.setError(error);
        return response;
    }

    public ResponseEntity<Object> processAndValidateRequest(Map<String, Object> requestBody, String apiAction, String onApiAction, String kafkaTopic) {
        Response response = new Response();
        try {
            Request request = new Request(requestBody, apiAction);
            setResponseParams(request, response);
            processAndValidateRequest(onApiAction, request, requestBody, apiAction);
            System.out.println("http respond sent");
            return new ResponseEntity<>(response, HttpStatus.ACCEPTED);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("error   " + e);
            return exceptionHandler(response, e);
        }
    }

}