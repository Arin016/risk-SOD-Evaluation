package com.saviynt.sod.evaluation.service;

import com.saviynt.sod.evaluation.dao.SodConfigDao;
import com.saviynt.sod.evaluation.model.Risk;
import com.saviynt.sod.evaluation.model.UserAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Validates our evaluation results against the existing SODRISKS table.
 * Pure read-only — no writes. Produces a diff report.
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);
    private final JdbcTemplate jdbc;

    public ValidationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Compare our detected violations against existing SODRISKS.
     *
     * @param violationBitSets our results: "riskId###endpointKey" → BitSet of violating user indices
     * @param users the user list (to map index → userIdentifier)
     * @param risks the risk list (to map riskId)
     * @return validation report
     */
    public ValidationReport validate(Map<String, BitSet> violationBitSets,
                                     List<UserAccess> users,
                                     List<Risk> risks,
                                     List<Long> rulesetKeys) {
        // Build our set: (userIdentifier, riskId, endpointKey)
        Set<String> ourViolations = new HashSet<>();
        Map<Long, Risk> riskMap = new HashMap<>();
        risks.forEach(r -> riskMap.put(r.riskId(), r));

        for (var entry : violationBitSets.entrySet()) {
            String[] parts = entry.getKey().split("###");
            long riskId = Long.parseLong(parts[0]);
            long endpointKey = Long.parseLong(parts[1]);
            BitSet bits = entry.getValue();

            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                long userIdentifier = users.get(i).userKey();
                ourViolations.add(userIdentifier + "#" + riskId + "#" + endpointKey);
            }
        }

        // Load existing violations from SODRISKS
        String placeholders = rulesetKeys.stream().map(k -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = """
                SELECT s.USERIDENTIFIER, s.RISKKEY, s.ENDPOINTKEY
                FROM sodrisks s
                JOIN risks r ON s.RISKKEY = r.RISKID
                WHERE r.RULESETKEY IN (%s) AND s.STATUS IN (1, 2, 3)
                """.formatted(placeholders);

        Set<String> existingViolations = new HashSet<>();
        jdbc.query(sql, rulesetKeys.toArray(), (rs, rowNum) -> {
            long userIdentifier = rs.getLong(1);
            long riskKey = rs.getLong(2);
            long endpointKey = rs.getLong(3);
            existingViolations.add(userIdentifier + "#" + riskKey + "#" + endpointKey);
            return null;
        });

        // Compute diff
        Set<String> falsePositives = new HashSet<>(ourViolations);
        falsePositives.removeAll(existingViolations);

        Set<String> falseNegatives = new HashSet<>(existingViolations);
        falseNegatives.removeAll(ourViolations);

        Set<String> matches = new HashSet<>(ourViolations);
        matches.retainAll(existingViolations);

        log.info("=== VALIDATION REPORT ===");
        log.info("  Our violations:      {}", ourViolations.size());
        log.info("  Existing violations: {}", existingViolations.size());
        log.info("  Matches:             {}", matches.size());
        log.info("  False positives:     {} (we found, existing doesn't have)", falsePositives.size());
        log.info("  False negatives:     {} (existing has, we missed)", falseNegatives.size());

        if (!falsePositives.isEmpty()) {
            log.warn("  Sample false positives: {}", falsePositives.stream().limit(5).toList());
        }
        if (!falseNegatives.isEmpty()) {
            log.warn("  Sample false negatives: {}", falseNegatives.stream().limit(5).toList());
        }

        return new ValidationReport(ourViolations.size(), existingViolations.size(),
                matches.size(), falsePositives.size(), falseNegatives.size(),
                falsePositives.stream().limit(10).toList(),
                falseNegatives.stream().limit(10).toList());
    }

    public record ValidationReport(
            int ourCount,
            int existingCount,
            int matches,
            int falsePositives,
            int falseNegatives,
            List<String> sampleFalsePositives,
            List<String> sampleFalseNegatives
    ) {
        public boolean isCorrect() {
            return falsePositives == 0 && falseNegatives == 0;
        }
    }
}
