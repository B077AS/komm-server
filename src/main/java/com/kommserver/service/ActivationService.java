package com.kommserver.service;

import com.kommserver.model.db.Installation;
import com.kommserver.model.dto.request.InstallationValidationRequest;
import com.kommserver.model.dto.response.InstallationValidationResponse;
import com.kommserver.repository.InstallationRepository;
import com.kommserver.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.security.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivationService {

    private final JwtUtil jwtUtil;
    private final RestTemplate restTemplate;
    private final InstallationRepository installationRepository;

    @Value("${api.url}")
    private String hubBaseUrl;

    public boolean activateInstallation(String setupToken) {
        try {
            Security.addProvider(new BouncyCastleProvider());

            String csrPem = jwtUtil.generateCsrPem(setupToken);

            InstallationValidationRequest body = InstallationValidationRequest.builder()
                    .setupToken(setupToken)
                    .csr(csrPem)
                    .build();

            ResponseEntity<InstallationValidationResponse> response = restTemplate.postForEntity(
                    hubBaseUrl + "/api/installations/validate",
                    body,
                    InstallationValidationResponse.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Hub rejected registration: {}", response.getStatusCode());
                return false;
            }

            InstallationValidationResponse installationResponse=response.getBody();

            Installation installation = Installation.builder()
                    .installationId(installationResponse.getInstallationId())
                    .installationName(installationResponse.getInstallationName())
                    .certificate(installationResponse.getCertificate())
                    .hubPublicKey(installationResponse.getHubPublicKey())
                    .ownerId(installationResponse.getOwnerId())
                    .build();
            installationRepository.save(installation);

            jwtUtil.setHubPublicKeyFromPem(installationResponse.getHubPublicKey());

            log.info("Registration complete — certificate issued by hub CA");
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("Registration failed: {}", e.getMessage());
            return false;
        }
    }
}