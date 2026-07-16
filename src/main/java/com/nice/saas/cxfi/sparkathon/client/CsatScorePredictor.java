package com.nice.saas.cxfi.sparkathon.client;

import com.nice.saas.cxfi.sparkathon.model.TopicAiResultResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic predicted-CSAT calculator driven entirely by TopicAI inference output.
 *
 * <p>Algorithm (all weights tuned to produce a 1.0–5.0 output):
 * <ol>
 *   <li>Base score = 3.5 (neutral mid-point).</li>
 *   <li>Primary topic confidence multiplier: higher TopicAI score → more confident signal,
 *       scaled to ±0.8.</li>
 *   <li>Category sentiment adjustment: billing / cancellation / complaint categories push the
 *       score down; access / order / refund categories are mildly negative; tech is neutral.</li>
 *   <li>callDriver amplifier: when the primary topic is flagged as the call driver the
 *       category adjustment is amplified by 1.5×.</li>
 *   <li>Secondary-topic drag: each additional secondary topic lowers the predicted CSAT by a
 *       small fixed amount (complex interactions have lower satisfaction).</li>
 *   <li>Action-based lift: detected positive actions (resolution, confirmation, apology) add a
 *       small boost.</li>
 *   <li>Result is clamped to [1.0, 5.0] and rounded to 2 decimal places.</li>
 * </ol>
 */
@Component
public class CsatScorePredictor {

    // Weights
    private static final double BASE_SCORE          = 3.5;
    private static final double MAX_CONFIDENCE_DELTA = 0.8;   // primary topic score 1.0 → ±0.8 shift
    private static final double CALL_DRIVER_AMPLIFIER = 1.5;
    private static final double SECONDARY_DRAG      = 0.12;   // per secondary topic
    private static final double MAX_SECONDARY_DRAG  = 0.4;
    private static final double ACTION_LIFT         = 0.15;   // per positive action detected
    private static final double MAX_ACTION_LIFT     = 0.4;

    /**
     * Computes a predicted CSAT in the range [1.0, 5.0] from a TopicAI result.
     * Returns {@code null} if the result or its payload is absent.
     */
    public Double predict(TopicAiResultResponse result) {
        if (result == null || result.getPayload() == null) {
            return null;
        }

        TopicAiResultResponse.Payload payload = result.getPayload();
        TopicAiResultResponse.TopicDetail primary = payload.getPrimaryTopic();
        if (primary == null) {
            return null;
        }

        double score = BASE_SCORE;

        // 1. Category sentiment adjustment
        double categoryAdj = categoryAdjustment(primary.getCategory(), primary.getName());

        // 2. Amplify if this topic drove the call
        if (Boolean.TRUE.equals(primary.getCallDriver())) {
            categoryAdj *= CALL_DRIVER_AMPLIFIER;
        }
        score += categoryAdj;

        // 3. Scale by TopicAI confidence: high confidence → stronger signal
        double confidence = primary.getScore() != null ? clamp(primary.getScore(), 0.0, 1.0) : 0.5;
        // Re-centre around 0.5 so low confidence pulls toward neutral
        double confidenceFactor = (confidence - 0.5) * 2.0; // [-1, +1]
        score += confidenceFactor * MAX_CONFIDENCE_DELTA * Math.signum(categoryAdj == 0 ? -0.1 : categoryAdj);

        // 4. Secondary-topic drag
        List<TopicAiResultResponse.TopicDetail> secondary = payload.getSecondaryTopics();
        if (secondary != null && !secondary.isEmpty()) {
            double drag = Math.min(secondary.size() * SECONDARY_DRAG, MAX_SECONDARY_DRAG);
            score -= drag;
        }

        // 5. Action-based lift
        List<TopicAiResultResponse.TopicAction> actions = payload.getActions();
        if (actions != null && !actions.isEmpty()) {
            long positiveActions = actions.stream()
                    .filter(a -> isPositiveAction(a.getName()))
                    .count();
            score += Math.min(positiveActions * ACTION_LIFT, MAX_ACTION_LIFT);
        }

        return round(clamp(score, 1.0, 5.0));
    }

    /**
     * Category/name → sentiment delta relative to neutral (0.0).
     * Negative values = bad experience; positive = good.
     */
    private static double categoryAdjustment(String category, String name) {
        String c = normalise(category);
        String n = normalise(name);

        // Explicit complaint / escalation
        if (contains(c, n, "complaint", "escalat", "churn", "cancel")) {
            return -1.2;
        }
        // Billing disputes
        if (contains(c, n, "billing", "overcharg", "charge", "invoice", "refund")) {
            return -0.8;
        }
        // Technical problems
        if (contains(c, n, "tech", "outage", "broken", "error", "issue", "problem", "fail")) {
            return -0.6;
        }
        // Account / access friction
        if (contains(c, n, "access", "login", "password", "locked", "account")) {
            return -0.4;
        }
        // Order / fulfilment (mild dissatisfaction)
        if (contains(c, n, "order", "delivery", "shipment", "delay")) {
            return -0.3;
        }
        // Positive / informational
        if (contains(c, n, "inquiry", "information", "question", "product", "upgrade", "renewal")) {
            return 0.2;
        }
        return 0.0; // neutral / unknown
    }

    private static boolean contains(String category, String name, String... keywords) {
        for (String kw : keywords) {
            if (category.contains(kw) || name.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPositiveAction(String actionName) {
        if (actionName == null) return false;
        String a = actionName.toLowerCase(Locale.ROOT);
        return a.contains("resolv") || a.contains("confirm") || a.contains("apolog")
                || a.contains("escalat") || a.contains("callback") || a.contains("refund approved");
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
