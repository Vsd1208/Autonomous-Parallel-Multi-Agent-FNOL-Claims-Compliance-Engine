package com.guidewire.fnol.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.guidewire.fnol.common.Models.ClaimHistory;
import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.common.Models.Endorsement;
import com.guidewire.fnol.common.Models.Policy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * STARTING POINT, NEVER RUN AGAINST A REAL TENANT (profile {@code guidewire-cloud-policy}). Tested only against a simulator of what is
 * written here.
 *
 * <p>From Guidewire's public documentation: {@code POST /policy/v1/search/policies} finds a policy by number, and
 * {@code POST /claim/v1/search/claims-v2} accepts a policy number. <b>Assumed, to be checked in the sandbox:</b> every field name below.
 * <b>Not found in the public documentation:</b> a PolicyCenter coverages path, which varies by line of business, so it is a setting
 * with no default, and without it {@link #getCoverages} refuses. A policy's endorsements are read if present; absent ones count as none.
 */
@Component
@Profile("guidewire-cloud-policy")
public class GuidewireCloudPolicyCenterClient implements PolicyCenterClient {

    private static final String POLICY_SEARCH = "/policy/v1/search/policies";
    private static final String CLAIM_SEARCH = "/claim/v1/search/claims-v2";

    private final GuidewireCloudHttp http;
    private final String coveragesPath;
    private final Map<String, String> lineCodes;

    public GuidewireCloudPolicyCenterClient(RestTemplate rest,
                                            @Value("${guidewire.cloud.base-url:}") String baseUrl,
                                            @Value("${guidewire.cloud.token-url:}") String tokenUrl,
                                            @Value("${guidewire.cloud.client-id:}") String clientId,
                                            @Value("${guidewire.cloud.client-secret:}") String clientSecret,
                                            @Value("${guidewire.cloud.coverages-path:}") String coveragesPath,
                                            @Value("${guidewire.cloud.line-codes:}") String lineCodes) {
        GuidewireTokenProvider.requireConfigured("The Guidewire Cloud policy client", Map.of("guidewire.cloud.base-url", baseUrl,
                "guidewire.cloud.token-url", tokenUrl, "guidewire.cloud.client-id", clientId, "guidewire.cloud.client-secret", clientSecret));
        this.http = new GuidewireCloudHttp(rest, baseUrl, new GuidewireTokenProvider(rest, tokenUrl, clientId, clientSecret, Clock.systemUTC()), Thread::sleep);
        this.coveragesPath = coveragesPath;
        this.lineCodes = parseLineCodes(lineCodes);
    }

    /** "PersonalAuto:AUTO,Homeowners:HOME": a tenant's product codes are its own, so the mapping is configuration. */
    static Map<String, String> parseLineCodes(String setting) {
        if (setting == null || setting.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(setting.split(",")).map(p -> p.trim().split(":", 2)).filter(p -> p.length == 2)
                .collect(Collectors.toMap(p -> p[0].trim(), p -> p[1].trim()));
    }

    private JsonNode search(String path, String policyNumber) {
        return http.call(HttpMethod.POST, path, Map.of("policyNumber", policyNumber)).path("data");
    }

    /** The policy with this number; null when there is none, as for the mock's 404. Two matches are an error: no guessing between them. */
    @Override
    public Policy getPolicy(String policyNumber) {
        JsonNode found = search(POLICY_SEARCH, policyNumber);
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            throw new IllegalStateException("The policy search returned " + found.size() + " policies for one number");
        }
        JsonNode a = found.get(0).path("attributes");
        List<Endorsement> endorsements = new ArrayList<>();
        for (JsonNode e : a.path("endorsements")) {
            endorsements.add(new Endorsement(date(e, "effectiveDate"), e.path("change").asText(null), e.path("coverageType").asText(null),
                    amount(e, "limit"), amount(e, "deductible")));
        }
        return new Policy(a.path("policyNumber").asText(policyNumber), lineCodes.get(code(a, "product")), code(a, "status"),
                date(a, "effectiveDate"), date(a, "expirationDate"), a.path("insuredName").asText(null), code(a, "jurisdiction"),
                null, List.of(), null, a.path("currency").asText(null), date(a, "cancellationDate"), List.copyOf(endorsements));
    }

    @Override
    public List<Coverage> getCoverages(String policyNumber) {
        if (coveragesPath == null || coveragesPath.isBlank()) {
            throw new UnsupportedOperationException("Coverages have no verified Cloud API mapping: set guidewire.cloud.coverages-path once a sandbox shows the path; see docs/guidewire-integration.md");
        }
        List<Coverage> out = new ArrayList<>();
        for (JsonNode c : http.call(HttpMethod.GET, coveragesPath, null, policyNumber).path("data")) {
            JsonNode a = c.path("attributes");
            out.add(new Coverage(code(a, "coverageType"), amount(a, "limit"), amount(a, "deductible")));
        }
        return List.copyOf(out);
    }

    @Override
    public List<ClaimHistory> getPolicyClaimHistory(String policyNumber) {
        List<ClaimHistory> out = new ArrayList<>();
        for (JsonNode c : search(CLAIM_SEARCH, policyNumber)) {
            JsonNode a = c.path("attributes");
            LocalDate loss = date(a, "lossDate");
            if (loss == null) {
                throw new IllegalStateException("A prior claim came back with no loss date, so claim frequency cannot be counted");
            }
            out.add(new ClaimHistory(c.path("id").asText(a.path("id").asText(null)), loss, code(a, "state"), amount(a, "totalPaid"), null));
        }
        out.sort(java.util.Comparator.comparing(ClaimHistory::lossDate).reversed());
        return List.copyOf(out);
    }

    /** A typekey's code, or the plain value. */
    private static String code(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.hasNonNull("code") ? v.path("code").asText() : v.isTextual() ? v.asText() : null;
    }

    /** The date part of an ISO date or date-time; null when absent. A malformed date throws: it must not read as "no date". */
    private static LocalDate date(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        return v == null || v.isBlank() ? null : LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
    }

    /** A plain number or a currency amount ({"amount": ...}); null when absent. */
    private static BigDecimal amount(JsonNode node, String field) {
        JsonNode v = node.path(field);
        JsonNode number = v.has("amount") ? v.path("amount") : v;
        return number.isNull() || number.isMissingNode() ? null : new BigDecimal(number.asText());
    }
}
