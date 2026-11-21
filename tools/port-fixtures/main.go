// Port-verification tool for desktop 1b80d72e. Run only inside the exported source tree.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"time"

	"github.com/mapledaemon/MagicHandy/internal/chat"
	"github.com/mapledaemon/MagicHandy/internal/config"
	"github.com/mapledaemon/MagicHandy/internal/handyble"
	"github.com/mapledaemon/MagicHandy/internal/motion"
)

type record struct {
	ID         string `json:"case_id"`
	Role       string `json:"role"`
	Path       string `json:"path"`
	SHA        string `json:"sha256"`
	Locale     string `json:"locale,omitempty"`
	Capability string `json:"capability,omitempty"`
	Mode       string `json:"mode,omitempty"`
	Revision   *int   `json:"settings_revision,omitempty"`
	Clock      *int64 `json:"wall_unix_ms,omitempty"`
	Start      *int64 `json:"stream_start_ms,omitempty"`
}

type exporter struct {
	dir   string
	cases []record
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}
func read(path string) []byte {
	data, err := os.ReadFile(path)
	must(err)
	return data
}

func (e *exporter) bytes(r record, data []byte) {
	// Fixtures are text. Match the checkout's .gitattributes before hashing so a copy
	// of the goldens through Git on Windows retains the same manifest digest.
	data = bytes.ReplaceAll(data, []byte("\r\n"), []byte("\n"))
	path := filepath.Join(e.dir, r.Path)
	must(os.MkdirAll(filepath.Dir(path), 0755))
	must(os.WriteFile(path, data, 0644))
	digest := sha256.Sum256(data)
	r.SHA = hex.EncodeToString(digest[:])
	e.cases = append(e.cases, r)
}
func (e *exporter) json(r record, value any) {
	data, err := json.MarshalIndent(value, "", "  ")
	must(err)
	e.bytes(r, append(data, '\n'))
}

func main() {
	dir := flag.String("output", "", "destination")
	source := flag.String("source", "", "snapshot SHA")
	flag.Parse()
	if *dir == "" || *source != "1b80d72ee4b90a5ab80a9bb3fe373b1640388f80" {
		panic("expected an output directory and the reviewed SHA")
	}
	e := exporter{dir: *dir}
	e.atlas()
	e.creativeMatrix()
	e.layeredScores()
	e.tempo()
	e.wire()
	e.hosted()
	e.personas()
	e.localHistory()
	e.content()
	manifest, err := json.MarshalIndent(map[string]any{"format_version": 1, "source_sha": *source, "cases": e.cases}, "", "  ")
	must(err)
	must(os.WriteFile(filepath.Join(*dir, "manifest.json"), append(manifest, '\n'), 0644))
	fmt.Printf("Exported %d fixture records.\n", len(e.cases))
}

func (e *exporter) atlas() {
	settings := config.DefaultSettings().Motion
	settings.SpeedMinPercent, settings.SpeedMaxPercent = 10, 43
	count := 0
	for _, definition := range motion.BuiltinPatternDefinitions() {
		// The ordinary atlas's bounded-ramp curves exercise the buffered sampler without
		// requiring the continuous score compiler. Catalog content stays in tests only.
		if !slices.Contains(definition.Tags, motion.TagDeprecated) {
			continue
		}
		for _, speed := range []int{10, 25, 43} {
			target := motion.MotionTarget{PatternID: definition.ID, Pattern: &definition, SpeedPercent: speed}
			review := motion.ReviewMotionOutput(target, settings)
			if review.Error != "" || len(review.Wire) == 0 {
				panic(fmt.Sprintf("%s: %s", definition.ID, review.Error))
			}
			plan := motion.NewMotionPlan("fixture", target, settings, 0, 0, time.Unix(0, 0))
			id := fmt.Sprintf("%s-%02d", definition.ID, speed)
			zero := int64(0)
			revision := 1
			e.json(record{ID: id, Role: "motion-atlas", Path: "motion/" + id + ".json", Mode: "bounded-ramp", Revision: &revision, Clock: &zero, Start: &zero}, map[string]any{
				"settings": settings, "speed_percent": speed, "period_ms": plan.PeriodMillis, "authored": plan.Target.Pattern.Points,
				"cycle_ms": plan.Target.Pattern.CycleMillis, "frames": review.Wire,
			})
		}
		count++
		if count == 7 {
			return
		}
	}
	panic("missing atlas inputs")
}

func (e *exporter) wire() {
	var ble []map[string]any
	must(json.Unmarshal(read("internal/handyble/testdata/browser-wire-vectors.json"), &ble))
	for _, method := range []handyble.Method{handyble.StopCurrentMode, handyble.State} {
		request := handyble.Request{ID: 10, Method: method}
		raw, err := handyble.Encode(request)
		must(err)
		path := "rpc/stop-current-mode"
		if method == handyble.State {
			path = "hsp/state"
		}
		ble = append(ble, map[string]any{"path": path, "id": request.ID, "body": map[string]any{}, "hex": hex.EncodeToString(raw)})
	}
	for i, v := range ble {
		id := fmt.Sprintf("handy-ble-%02d", i+1)
		e.json(record{ID: id, Role: "ble-wire", Path: "wire/" + id + ".json"}, v)
	}
	var cloud []map[string]any
	must(json.Unmarshal(read("internal/transport/testdata/cloud_requests.golden.json"), &cloud))
	for _, v := range cloud {
		id := "handy-cloud-" + v["operation"].(string)
		e.json(record{ID: id, Role: "cloud-wire", Path: "wire/" + id + ".json"}, v)
	}
}

func (e *exporter) content() {
	for _, locale := range []string{"en", "es", "pt-BR", "ja", "zh-Hans"} {
		e.bytes(record{ID: "translations-" + locale, Role: "translations", Locale: locale, Path: "content/locales/" + locale + ".json"}, read("web/src/i18n/locales/"+locale+".json"))
	}
	e.json(record{ID: "prompt-sets", Role: "content", Path: "content/prompt-sets.json"}, chat.BuiltinPromptSets())
	s := config.DefaultSettings().LLM
	// defaultPersonaView, internal/httpapi/personas.go. The sole built-in uses the settings defaults.
	e.json(record{ID: "personas", Role: "content", Path: "content/personas.json"}, []map[string]string{{"name": "MagicHandy", "description": s.PersonaDescription, "chat_voice": s.ChatVoice, "prompt_set_id": s.PromptSet}})
	e.bytes(record{ID: "notices", Role: "content", Path: "content/notices.json"}, read("internal/notices/catalog.json"))
	for _, name := range []string{"mark", "star"} {
		e.bytes(record{ID: "brand-" + name, Role: "brand", Path: "content/" + name + ".svg"}, read("assets/brand/"+name+".svg"))
	}
}
