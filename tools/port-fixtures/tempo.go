package main

import "github.com/mapledaemon/MagicHandy/internal/motion"

func (e *exporter) tempo() {
	for _, r := range motion.TempoRoutines() {
		spec := motion.TempoSpec{Mode: "routine", Routine: r.ID, Seed: 431}
		sampler, err := motion.TempoReviewSampler(spec, 0, 19)
		must(err)
		var levels []float64
		for _, at := range []int64{0, 150, 850, 7000, 18500, 47000, 63000, 122500, 243000, 900000} {
			levels = append(levels, sampler(at))
		}
		e.json(record{ID: "tempo-" + r.ID, Role: "tempo", Mode: "routine", Path: "motion/tempo/" + r.ID + ".json"}, map[string]any{"score": spec, "from": 19, "times": []int{0, 150, 850, 7000, 18500, 47000, 63000, 122500, 243000, 900000}, "levels": levels})
	}
}
