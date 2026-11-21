//go:build magichandy_labs

// Overlay this file in alpha.89 internal/motion alongside desktop_capture_test.go.
package motion

import (
	"encoding/json"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/mapledaemon/MagicHandy/internal/config"
)

type bridgeReviewCase struct {
	Wire      [][2]float64 `json:"wire"`
	Turns     []int64      `json:"turns"`
	Gap       int64        `json:"hard_gap_ms"`
	Preferred int64        `json:"preferred_ms"`
	Steps     int          `json:"steps"`
	Error     string       `json:"error,omitempty"`
}

func TestPhoneBridgeReference(t *testing.T) {
	destination := os.Getenv("MAGICHANDY_LINEAR_CAPTURE")
	if destination == "" {
		t.Skip("manual motion atlas")
	}
	settings := config.DefaultSettings().Motion
	settings.SpeedMinPercent, settings.SpeedMaxPercent = 1, 100
	settings.HandyModel = config.HandyModelOriginal
	results := map[string]bridgeReviewCase{}
	for _, item := range []struct {
		name  string
		speed int
		floor int64
	}{
		{"full", 25, 110}, {"short", 25, 110}, {"short", 100, 110}, {"compound", 60, 110}, {"minimum-width", 100, 330},
	} {
		spec := motionPathFixtures(item.speed)[item.name]
		target, err := FlowTarget(spec, settings)
		if err != nil {
			t.Fatal(err)
		}
		plan := NewMotionPlan("bridge-review", target, settings, 0, 0, time.Unix(0, 0))
		e := &Engine{plan: plan, settings: settings, chunkSize: defaultChunkSize, sampleInterval: defaultSampleInterval,
			preservePlanKnots: true, positionResolutionPercent: 1, resolutionAfterStrokeWindow: true,
			minimumPointIntervalMillis: item.floor, preferredPointIntervalMillis: 180}
		c := bridgeReviewCase{Wire: [][2]float64{}, Turns: plan.knotTimesBetween(0, 12001), Gap: item.floor, Preferred: 180, Steps: 100}
		for e.nextSampleMillis <= 12000 {
			points, err := e.nextMotionSamplesLocked()
			if err != nil {
				c.Error = err.Error()
				break
			}
			for _, p := range points {
				c.Wire = append(c.Wire, [2]float64{float64(p.TimeMillis), p.PositionPercent})
			}
		}
		results[fmt.Sprintf("%s-%03d", item.name, item.speed)] = c
	}
	data, err := json.Marshal(results)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(destination, data, 0644); err != nil {
		t.Fatal(err)
	}
}
