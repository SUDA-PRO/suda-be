package org.egov.pt.calculator.service.strategy.tenants;

import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pt.calculator.service.*;
import org.egov.pt.calculator.service.strategy.TenantBasedEstimationStrategy;
import org.egov.pt.calculator.util.*;
import org.egov.pt.calculator.validator.CalculationValidator;
import org.egov.pt.calculator.web.models.BillingSlab;
import org.egov.pt.calculator.web.models.Calculation;
import org.egov.pt.calculator.web.models.CalculationCriteria;
import org.egov.pt.calculator.web.models.TaxHeadEstimate;
import org.egov.pt.calculator.web.models.collections.Payment;
import org.egov.pt.calculator.web.models.demand.Category;
import org.egov.pt.calculator.web.models.demand.Demand;
import org.egov.pt.calculator.web.models.demand.TaxHeadMaster;
import org.egov.pt.calculator.web.models.demand.TaxPeriod;
import org.egov.pt.calculator.web.models.property.Property;
import org.egov.pt.calculator.web.models.property.PropertyDetail;
import org.egov.pt.calculator.web.models.property.RequestInfoWrapper;
import org.egov.pt.calculator.web.models.property.Unit;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static org.egov.pt.calculator.util.CalculatorConstants.*;

/**
 * DefaultEstimationStrategy - Default implementation of TenantBasedEstimationStrategy
 * This strategy contains the existing calculation logic and is used as fallback
 * when no tenant-specific implementation is found
 *
 * @author PT Calculator Service
 * @version 1.0
 */
@Service
@Slf4j
public class DefaultEstimationStrategy implements TenantBasedEstimationStrategy {

    @Autowired
    private PayService payService;

    @Autowired
    private MasterDataService mDataService;

    @Autowired
    private PBFirecessUtils firecessUtils;

    @Autowired
    private EnrichmentService enrichmentService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private EstimationCommonUtil estimationCommonUtil;

    @Value("${customization.pbfirecesslogic:false}")
    Boolean usePBFirecessLogic;

    @Override
    public Calculation calculateTax(CalculationCriteria criteria, RequestInfo requestInfo, Map<String, Object> masterMap) throws Exception {
        log.info("DefaultEstimationStrategy - Starting tax calculation");
        try {
            return getCalculation(requestInfo, criteria, masterMap);
        } catch (Exception e) {
            log.error("Error in DefaultEstimationStrategy.calculateTax: {}", e.getMessage(), e);
            throw new CustomException("DEFAULT_ESTIMATION_ERROR", "Default estimation calculation failed: " + e.getMessage());
        }
    }

    @Override
    public String getStrategyName() {
        return "DEFAULT_ESTIMATION_STRATEGY";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    /**
     * Prepares Calculation Response based on the provided TaxHeadEstimate List
     * All the credit taxHeads will be payable and all debit tax heads will be deducted.
     */
    private Calculation getCalculation(RequestInfo requestInfo, CalculationCriteria criteria, Map<String, Object> masterMap) {
        log.info("DefaultEstimationStrategy - Preparing calculation");

        Map<String, List> estimatesAndBillingSlabs = getEstimationMap(criteria, requestInfo, masterMap);

        List<TaxHeadEstimate> estimates = estimatesAndBillingSlabs.get("estimates");
        List<String> billingSlabIds = estimatesAndBillingSlabs.get("billingSlabIds");

        return estimationCommonUtil.getCalculation(requestInfo, criteria, masterMap, estimates, billingSlabIds);
    }

    /**
     * Generates a Map with estimates and billing slab IDs
     */
    private Map<String, List> getEstimationMap(CalculationCriteria criteria, RequestInfo requestInfo, Map<String, Object> masterMap) {
        log.debug("DefaultEstimationStrategy - Calculating estimation map");

        BigDecimal taxAmt = BigDecimal.ZERO;
        BigDecimal usageExemption = BigDecimal.ZERO;
        Property property = criteria.getProperty();
        PropertyDetail detail = property.getPropertyDetails().get(0);
        String assessmentYear = detail.getFinancialYear();
        String tenantId = property.getTenantId();

        if (criteria.getFromDate() == null || criteria.getToDate() == null)
            enrichmentService.enrichDemandPeriod(criteria, assessmentYear, masterMap);

        List<BillingSlab> filteredBillingSlabs = estimationCommonUtil.getCommonSlabsFiltered(property, criteria.getFinancialYear(), requestInfo);

        Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap = new HashMap<>();
        Map<String, JSONArray> timeBasedExemptionMasterMap = new HashMap<>();
        mDataService.setPropertyMasterValues(requestInfo, tenantId, propertyBasedExemptionMasterMap,
                timeBasedExemptionMasterMap);

        List<String> billingSlabIds = new LinkedList<>();

        if (PT_TYPE_VACANT_LAND.equalsIgnoreCase(detail.getPropertyType()) && filteredBillingSlabs.size() != 1)
            throw new CustomException(PT_ESTIMATE_BILLINGSLABS_UNMATCH_VACANCT, PT_ESTIMATE_BILLINGSLABS_UNMATCH_VACANT_MSG
                    .replace("{count}", String.valueOf(filteredBillingSlabs.size())));

        else if (PT_TYPE_VACANT_LAND.equalsIgnoreCase(detail.getPropertyType())) {
            taxAmt = taxAmt.add(BigDecimal.valueOf(filteredBillingSlabs.get(0).getUnitRate() * detail.getLandArea()));
        } else {
            double unBuiltRate = 0.0;
            int groundUnitsCount = 0;
            Double groundUnitsArea = 0.0;
            int i = 0;

            for (Unit unit : detail.getUnits()) {
                BillingSlab slab = estimationCommonUtil.getCommonSlabForCalc(filteredBillingSlabs, unit);
                BigDecimal currentUnitTax = estimationCommonUtil.getCommonTaxForUnit(slab, unit);
                billingSlabIds.add(slab.getId() + "|" + i);

                if (unit.getFloorNo().equalsIgnoreCase("0")) {
                    groundUnitsCount += 1;
                    groundUnitsArea += unit.getUnitArea();
                    if (null != slab.getUnBuiltUnitRate())
                        unBuiltRate += slab.getUnBuiltUnitRate();
                }
                taxAmt = taxAmt.add(currentUnitTax);
                usageExemption = usageExemption
                        .add(estimationCommonUtil.getCommonExemption(unit, currentUnitTax, assessmentYear, propertyBasedExemptionMasterMap));
                i++;
            }

            taxAmt = taxAmt.add(estimationCommonUtil.getCommonUnBuiltRate(detail, unBuiltRate, groundUnitsCount, groundUnitsArea));

            if (detail.getUnits().size() == 1)
                usageExemption = estimationCommonUtil.getCommonExemption(detail.getUnits().get(0), taxAmt, assessmentYear,
                        propertyBasedExemptionMasterMap);
        }

        List<TaxHeadEstimate> taxHeadEstimates = getEstimatesForTax(requestInfo, taxAmt, usageExemption, property, propertyBasedExemptionMasterMap,
                timeBasedExemptionMasterMap, masterMap);

        Map<String, List> estimatesAndBillingSlabs = new HashMap<>();
        estimatesAndBillingSlabs.put("estimates", taxHeadEstimates);
        estimatesAndBillingSlabs.put("billingSlabIds", billingSlabIds);

        return estimatesAndBillingSlabs;
    }

    private List<TaxHeadEstimate> getEstimatesForTax(RequestInfo requestInfo, BigDecimal taxAmt, BigDecimal usageExemption, Property property,
                                                     Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap,
                                                     Map<String, JSONArray> timeBasedExemeptionMasterMap, Map<String, Object> masterMap) {

        PropertyDetail detail = property.getPropertyDetails().get(0);
        BigDecimal payableTax = taxAmt;
        List<TaxHeadEstimate> estimates = new ArrayList<>();

        String assessmentYear = detail.getFinancialYear();
        estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_TAX).estimateAmount(taxAmt.setScale(2, 2)).build());

        usageExemption = usageExemption.setScale(2, 2).negate();
        estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_UNIT_USAGE_EXEMPTION).estimateAmount(
                usageExemption).build());
        payableTax = payableTax.add(usageExemption);

        BigDecimal userExemption = estimationCommonUtil.getOwnerExemption(detail.getOwners(), payableTax, assessmentYear,
                propertyBasedExemptionMasterMap).setScale(2, 2).negate();
        estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_OWNER_EXEMPTION).estimateAmount(userExemption).build());
        payableTax = payableTax.add(userExemption);

        List<Object> fireCessMasterList = timeBasedExemeptionMasterMap.get(CalculatorConstants.FIRE_CESS_MASTER);
        BigDecimal fireCess;

        if (usePBFirecessLogic) {
            fireCess = firecessUtils.getPBFireCess(payableTax, assessmentYear, fireCessMasterList, detail);
            estimates.add(
                    TaxHeadEstimate.builder().taxHeadCode(PT_FIRE_CESS).estimateAmount(fireCess.setScale(2, 2)).build());
        } else {
            fireCess = mDataService.getCess(payableTax, assessmentYear, fireCessMasterList);
            estimates.add(
                    TaxHeadEstimate.builder().taxHeadCode(PT_FIRE_CESS).estimateAmount(fireCess.setScale(2, 2)).build());
        }

        List<Object> cancerCessMasterList = timeBasedExemeptionMasterMap.get(CalculatorConstants.CANCER_CESS_MASTER);
        BigDecimal cancerCess = mDataService.getCess(payableTax, assessmentYear, cancerCessMasterList);
        estimates.add(
                TaxHeadEstimate.builder().taxHeadCode(PT_CANCER_CESS).estimateAmount(cancerCess.setScale(2, 2)).build());

        Map<String, Map<String, Object>> financialYearMaster = (Map<String, Map<String, Object>>) masterMap.get(FINANCIALYEAR_MASTER_KEY);
        Map<String, Object> finYearMap = financialYearMaster.get(assessmentYear);
        Long fromDate = (Long) finYearMap.get(FINANCIAL_YEAR_STARTING_DATE);
        Long toDate = (Long) finYearMap.get(FINANCIAL_YEAR_ENDING_DATE);

        TaxPeriod taxPeriod = TaxPeriod.builder().fromDate(fromDate).toDate(toDate).build();

        List<Payment> payments = new LinkedList<>();

        if (null != property.getPropertyId() && null != property.getTenantId()) {
            payments = paymentService.getPaymentsFromProperty(property, RequestInfoWrapper.builder().requestInfo(requestInfo).build());
        }

        Map<String, BigDecimal> rebatePenaltyMap = payService.applyPenaltyRebateAndInterest(payableTax, BigDecimal.ZERO,
                assessmentYear, timeBasedExemeptionMasterMap, payments, taxPeriod);

        if (null != rebatePenaltyMap) {
            BigDecimal rebate = rebatePenaltyMap.get(PT_TIME_REBATE);
            BigDecimal penalty = rebatePenaltyMap.get(PT_TIME_PENALTY);
            BigDecimal interest = rebatePenaltyMap.get(PT_TIME_INTEREST);
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_TIME_REBATE).estimateAmount(rebate).build());
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_TIME_PENALTY).estimateAmount(penalty).build());
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_TIME_INTEREST).estimateAmount(interest).build());
            payableTax = payableTax.add(rebate).add(penalty).add(interest);
        }

        if (null != detail.getAdhocPenalty())
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_ADHOC_PENALTY)
                    .estimateAmount(detail.getAdhocPenalty()).build());

        if (null != detail.getAdhocExemption() && detail.getAdhocExemption().compareTo(payableTax.add(fireCess)) <= 0) {
            estimates.add(TaxHeadEstimate.builder().taxHeadCode(PT_ADHOC_REBATE)
                    .estimateAmount(detail.getAdhocExemption().negate()).build());
        } else if (null != detail.getAdhocExemption()) {
            throw new CustomException(PT_ADHOC_REBATE_INVALID_AMOUNT, PT_ADHOC_REBATE_INVALID_AMOUNT_MSG + taxAmt);
        }
        return estimates;
    }
}

