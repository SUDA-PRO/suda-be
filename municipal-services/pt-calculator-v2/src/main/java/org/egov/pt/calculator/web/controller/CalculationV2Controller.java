package org.egov.pt.calculator.web.controller;

import org.egov.pt.calculator.service.EstimationService;
import org.egov.pt.calculator.service.TranslationService;
import org.egov.pt.calculator.web.models.CalculationReq;
import org.egov.pt.calculator.web.models.CalculationRes;
import org.egov.pt.calculator.web.models.propertyV2.AssessmentRequestV2;
import org.egov.pt.calculator.web.models.propertyV2.PropertyRequestV2;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * CalculationV2Controller - REST controller for property tax calculation API v2
 *
 * Handles tax estimation requests using locality-based dynamic strategy resolution.
 * The controller delegates to EstimationService which uses LocalityBasedTaxCalculationService
 * to resolve and apply the appropriate estimation strategy based on property locality.
 *
 * @author PT Calculator Service
 * @version 2.0
 */
@Controller
@RequestMapping("/propertytax/v2")
@Slf4j
public class CalculationV2Controller {

	private TranslationService translationService;

	private EstimationService estimationService;

	@Autowired
	public CalculationV2Controller(TranslationService translationService, EstimationService estimationService) {
		this.translationService = translationService;
		this.estimationService = estimationService;
	}

	/**
	 * Endpoint: POST /propertytax/v2/_estimate
	 *
	 * Calculates property tax estimation based on assessment request.
	 * The calculation uses locality-based dynamic strategy resolution:
	 * 1. Translates input to internal format
	 * 2. Extracts locality code from property address
	 * 3. Resolves appropriate estimation strategy (locality-specific or default)
	 * 4. Applies strategy to calculate tax
	 * 5. Returns calculated result
	 *
	 * @param assessmentRequestV2 the assessment request containing property details
	 * @return ResponseEntity with CalculationRes containing tax calculation result
	 * @throws CustomException if validation or calculation fails
	 */
	@PostMapping("/_estimate")
	public ResponseEntity<CalculationRes> getTaxEstimation(@RequestBody @Valid AssessmentRequestV2 assessmentRequestV2) {
		log.info("CalculationV2Controller.getTaxEstimation - Received tax estimation request");
		try {
			// Translate to internal format
			log.debug("Translating assessment request to internal format");
			CalculationReq calculationReq = translationService.translate(assessmentRequestV2);

			if (calculationReq == null || calculationReq.getCalculationCriteria() == null ||
				calculationReq.getCalculationCriteria().isEmpty()) {
				log.error("Invalid calculation request after translation");
				throw new CustomException("INVALID_REQUEST", "Invalid calculation request");
			}

			log.info("Assessment request translated successfully. Processing tax calculation");

			// Calculate tax using tenant-based strategy resolution
			CalculationRes calculationRes = estimationService.getTaxCalculation(calculationReq);

			log.info("Tax estimation completed successfully");
			return new ResponseEntity<>(calculationRes, HttpStatus.OK);

		} catch (CustomException ce) {
			log.error("Custom exception in getTaxEstimation - Code: {}, Message: {}", ce.getCode(), ce.getMessage());
			throw ce;
		} catch (Exception e) {
			log.error("Unexpected error in getTaxEstimation: {}", e.getMessage(), e);
			throw new CustomException("ESTIMATION_ERROR", "Error during tax estimation: " + e.getMessage());
		}
	}
}


