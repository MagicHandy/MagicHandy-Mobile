package main

import (
	"github.com/mapledaemon/MagicHandy/internal/chat"
	"github.com/mapledaemon/MagicHandy/internal/config"
	"github.com/mapledaemon/MagicHandy/internal/motion"
)

func (e *exporter) layeredScores() {
	settings := config.DefaultSettings().Motion
	settings.SpeedMinPercent, settings.SpeedMaxPercent = 1, 100
	field := chat.DefaultLayeredScore(42)
	phrase := field
	phrase.MinPercent, phrase.MaxPercent = 10, 90
	phrase.Layers = nil
	phrase.Phrase = &motion.FlowPhrase{Strokes: []motion.PhraseStroke{{BottomPercent: 10, TopPercent: 90, Count: 1, RatePercent: 100}, {BottomPercent: 65, TopPercent: 90, Count: 3, RatePercent: 55}}}
	regional := phrase
	regional.DirectionalPace = &motion.DirectionalPace{UpRatePercent: 100, DownRatePercent: 60}
	regional.Phrase = &motion.FlowPhrase{Regions: []motion.PhraseRegion{
		{PhraseStroke: motion.PhraseStroke{BottomPercent: 10, TopPercent: 35, Count: 2, RatePercent: 60}, Cycle: "up_down"},
		{PhraseStroke: motion.PhraseStroke{BottomPercent: 60, TopPercent: 90, Count: 2, RatePercent: 100}, Cycle: "down_up"},
	}}
	for _, c := range []struct {
		id    string
		score motion.FlowSpec
	}{{"field", field}, {"counted", phrase}, {"regional", regional}} {
		target, err := motion.FlowTarget(c.score, settings)
		must(err)
		review := motion.ReviewMotionOutput(target, settings)
		if review.Error != "" {
			panic(review.Error)
		}
		zero, rev := int64(0), 1
		e.json(record{ID: "layered-" + c.id, Role: "layered-score", Path: "motion/layered/" + c.id + ".json", Mode: "layered", Revision: &rev, Clock: &zero, Start: &zero},
			map[string]any{"score": c.score, "settings": settings, "period_ms": review.PeriodMillis, "frames": review.Wire})
	}
}
