// Source: cmd/motion-atlas/creative_v2.go at 1b80d72e.
package main

import (
	"fmt"
	"strings"

	"github.com/mapledaemon/MagicHandy/internal/config"
	"github.com/mapledaemon/MagicHandy/internal/motion"
)

// Keep the semantic inputs alongside quantized frames. Original Creative contributes
// comparison vectors only. The app does not expose the retired mode.
func (e *exporter) creativeMatrix() {
	settings := config.DefaultSettings().Motion
	settings.SpeedMinPercent, settings.SpeedMaxPercent = 1, 100
	for _, model := range []string{config.HandyModelOriginal, config.HandyModel2Standard, config.HandyModel2Pro} {
		settings.HandyModel = model
		for _, speed := range []int{10, 45, 85} {
			for _, scenario := range creativeScenarios() {
				spec, gesture := motion.DefaultFlowSpec(), motion.DefaultGestureSpec()
				gesture.FocusPercent, gesture.FocusRoamPercent = 100, 0
				spec.Gesture, spec.RangeFloorPercent, spec.SpeedPercent = &gesture, 10, speed
				scenario.edit(&spec)
				target, err := motion.FlowTarget(spec, settings)
				must(err)
				e.matrixCase(settings, speed, scenario.name, "creative-v2", spec, target)
			}
			for _, seed := range []uint32{17, 23681, 1763268511} {
				d := motion.NormalizeDynamicDefinition(motion.DynamicDefinition{
					CenterPercent: 50, SpanPercent: 90, SpanMinPercent: 25,
					SpanProfile: "wander", VariationPercent: 65, PhraseSeed: seed,
				})
				e.matrixCase(settings, speed, fmt.Sprintf("original-%d", seed), "creative-original", d,
					motion.MotionTarget{Dynamic: &d, SpeedPercent: speed})
			}
		}
	}
}

func (e *exporter) matrixCase(settings config.MotionSettings, speed int, name, mode string, score any, target motion.MotionTarget) {
	review := motion.ReviewMotionOutput(target, settings)
	if review.Error != "" || len(review.Wire) == 0 {
		panic(fmt.Sprintf("%s/%s/%d: %s", name, settings.HandyModel, speed, review.Error))
	}
	id := fmt.Sprintf("%s-%s-%02d", strings.ReplaceAll(strings.ToLower(name), " ", "-"), settings.HandyModel, speed)
	zero, revision := int64(0), 1
	e.json(record{ID: id, Role: "creative-atlas", Path: "motion/atlas/" + id + ".json", Mode: mode, Revision: &revision, Clock: &zero, Start: &zero}, map[string]any{
		"settings": settings, "speed_percent": speed, "score": score, "period_ms": review.PeriodMillis, "frames": review.Wire,
	})
}
