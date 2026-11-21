//go:build magichandy_labs

// This test is overlaid on the pinned desktop internal/motion package for an inert comparison.
package motion

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/mapledaemon/MagicHandy/internal/config"
)

type phoneReviewCase struct {
	Name   string       `json:"name"`
	Model  string       `json:"model"`
	Route  string       `json:"route"`
	Score  FlowSpec     `json:"score"`
	Period int64        `json:"period"`
	Turns  []int64      `json:"turns"`
	Loop   [][4]float64 `json:"loop"`
	Wire   [][2]int64   `json:"wire"`
	Error  string       `json:"error,omitempty"`
}

func TestPhoneMotionReference(t *testing.T) {
	dir := os.Getenv("MAGICHANDY_MOTION_REFERENCE")
	if dir == "" {
		t.Skip("manual motion atlas")
	}
	settings := config.DefaultSettings().Motion
	settings.SpeedMinPercent, settings.SpeedMaxPercent = 1, 100
	for _, model := range []string{config.HandyModelOriginal, config.HandyModel2Standard, config.HandyModel2Pro} {
		settings.HandyModel = model
		for _, speed := range []int{10, 25, 60, 100} {
			fixtures := motionPathFixtures(speed)
			accepted := layeredSpeedFixture("compound", speed)
			for i := range accepted.Phrase.Strokes {
				accepted.Phrase.Strokes[i].RatePercent = 25
			}
			fixtures["compound-slow"] = accepted
			fixtures["manual"] = ContinuousRecipes(speed)[0].Spec
			names := []string{"full", "short", "minimum-width", "compound", "compound-slow", "regional", "down-fast", "default", "softest", "cadence", "creative-v2"}
			if speed == 10 {
				names = []string{"manual"}
			} else if speed == 25 {
				names = append(names, "manual")
			}
			for _, name := range names {
				spec := fixtures[name]
				target, err := FlowTarget(spec, settings)
				if err != nil {
					t.Fatal(err)
				}
				plan := NewMotionPlan("mobile-review", target, settings, 0, 0, time.Unix(0, 0))
				for _, route := range []string{"cloud", "ble"} {
					example := phoneReviewCase{Name: name, Model: model, Route: route, Score: spec, Period: plan.PeriodMillis,
						Turns: plan.knotTimesBetween(0, 12001)}
					capturePhoneReference(&example, plan, settings, route)
					id := fmt.Sprintf("%s-%s-%03d-%s.json", model, name, speed, route)
					data, err := json.Marshal(example)
					if err != nil {
						t.Fatal(err)
					}
					if err := os.WriteFile(filepath.Join(dir, id), data, 0644); err != nil {
						t.Fatal(err)
					}
				}
			}
		}
	}
}

func capturePhoneReference(c *phoneReviewCase, plan MotionPlan, settings config.MotionSettings, route string) {
	factor := float64(plan.PeriodMillis) / float64(plan.curve.duration)
	gain := plan.focus.gain()
	for i := 0; i <= 1600; i++ {
		at := float64(plan.PeriodMillis) * float64(i) / 1600
		u := at / factor
		c.Loop = append(c.Loop, [4]float64{at, plan.focus.apply(plan.curve.sampleFloat(u)),
			plan.curve.velocityFloat(u) * gain * 1000 / factor, plan.curve.accelerationFloat(u) * gain * 1e6 / (factor * factor)})
	}
	e := &Engine{plan: plan, settings: settings, chunkSize: defaultChunkSize, sampleInterval: defaultSampleInterval,
		preservePlanKnots: true, positionResolutionPercent: 1, maximumChunkPoints: maximumAdaptiveChunkPoints}
	configurePathReview(e, route)
	for e.nextSampleMillis <= 12000 {
		points, err := e.nextMotionSamplesLocked()
		if err != nil {
			c.Error = err.Error()
			return
		}
		for _, p := range points {
			c.Wire = append(c.Wire, [2]int64{p.TimeMillis, int64(math.Round(p.PositionPercent))})
		}
	}
}
