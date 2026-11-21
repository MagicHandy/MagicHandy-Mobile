// Build the mobile prompt vocabulary from the pinned alpha.89 composer.
// Its HTTP client has a private RoundTripper and cannot open a socket.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"

	"github.com/mapledaemon/MagicHandy/internal/chat"
	"github.com/mapledaemon/MagicHandy/internal/llm"
	"github.com/mapledaemon/MagicHandy/internal/motion"
)

type capture struct {
	input struct {
		Input []struct{ Content string }
		Text  struct {
			Format struct{ Schema json.RawMessage }
		}
	}
}

func (c *capture) RoundTrip(r *http.Request) (*http.Response, error) {
	defer r.Body.Close()
	if err := json.NewDecoder(r.Body).Decode(&c.input); err != nil {
		return nil, err
	}
	return &http.Response{StatusCode: 403, Body: io.NopCloser(strings.NewReader("{}")), Header: make(http.Header)}, nil
}

type template struct {
	Domain int    `json:"domain"`
	Suffix string `json:"suffix,omitempty"`
	Lines  []int  `json:"lines"`
	Schema int    `json:"schema"`
}
type catalog struct {
	Lines     []string            `json:"lines"`
	Schemas   []json.RawMessage   `json:"schemas"`
	Templates map[string]template `json:"templates"`
}

type composerCapture struct {
	llm.Provider
	schema json.RawMessage
}

func (c *composerCapture) StreamChat(ctx context.Context, req llm.ChatRequest, delta func(string) error) (string, error) {
	c.schema = req.JSONSchema
	return c.Provider.StreamChat(ctx, req, delta)
}

func main() {
	out := flag.String("output", "", "mobile resource destination")
	personaOutput := flag.String("persona-output", "", "mobile persona section catalog")
	deltas := flag.String("voice-deltas", "", "reviewed mobile wording differences")
	flag.Parse()
	writePersonas(*personaOutput, *deltas)
	bank := catalog{Templates: map[string]template{}}
	lines, schemas := map[string]int{}, map[string]int{}
	for _, locale := range []struct{ tag, id string }{{"en", chat.DefaultPromptSetID}, {"es", chat.PromptSetIDSpanish}, {"pt-BR", chat.PromptSetIDPortugueseBrazil}, {"ja", chat.PromptSetIDJapanese}, {"zh-Hans", chat.PromptSetIDSimplifiedChinese}} {
		preset, ok := chat.BuiltinPromptSetByID(locale.id)
		if !ok {
			panic(locale.id)
		}
		for _, tier := range []string{"advanced", "advanced-note", "simple", "intermediate"} {
			for _, mode := range []chat.MotionMode{chat.MotionModeOff, chat.MotionModeLayered, chat.MotionModeCreativeV2} {
				for _, phase := range []string{"idle", "running", "playing", "paused", "auto", "hold", "plan", "plan-local", "speech", "talk"} {
					if phase != "idle" && mode == chat.MotionModeOff {
						continue
					}
					for _, outputs := range []string{"", "vibrator", "vibrator-only", "tempo", "tempo-air", "tempo-resume"} {
						tempo := strings.HasPrefix(outputs, "tempo")
						if tempo && (mode != chat.MotionModeLayered || outputs == "tempo-resume" && phase != "idle") {
							continue
						}
						if mode == chat.MotionModeOff && outputs != "" {
							continue
						}
						grouped := locale.tag == "en" && (tier == "simple" || tier == "intermediate") && mode == chat.MotionModeLayered && outputs == "" && (phase == "idle" || phase == "running" || phase == "playing" || phase == "paused")
						for _, compact := range []bool{false, true} {
							if compact && !grouped {
								continue
							}
							peer := new(capture)
							provider, err := llm.NewOpenAIResponsesProvider(llm.OpenAIOptions{PlanUsage: true, Model: "gpt-6.1-terra", ReasoningEffort: "low", ContextSize: 131072, BaseURL: "https://offline.invalid", Client: &http.Client{Transport: peer}, Token: func(context.Context) (string, error) { return "fixture", nil }})
							if err != nil {
								panic(err)
							}
							caps := chat.Capabilities{Motion: mode != chat.MotionModeOff, MotionMode: mode, AdvancedMotion: strings.HasPrefix(tier, "advanced"), ChatPlanNote: tier == "advanced-note", LocalInference: tier == "simple" || tier == "intermediate", SimpleMotion: tier == "simple", IntermediateMotion: tier == "intermediate", PreserveConversationText: strings.HasPrefix(tier, "advanced"), Voice: chat.VoiceUtility, ReplyLength: chat.ReplyLengthBalanced, HistoryMessages: 16, HistoryBytes: 8192}
							state := chat.MotionContext{SpeedMinPercent: 10, SpeedMaxPercent: 43, Running: phase != "idle" && phase != "paused", Paused: phase == "paused", Autopilot: phase == "auto" || phase == "hold", StandingHold: phase == "hold"}
							if phase != "idle" && phase != "running" && mode == chat.MotionModeLayered {
								s := chat.DefaultLayeredScore(25)
								state.Layered = &s
							}
							original := &composerCapture{Provider: provider}
							if tempo {
								t := &chat.TempoContext{Leads: true, Ready: true, Running: state.Running, AirAllowed: outputs == "tempo-air"}
								if outputs == "tempo-resume" {
									t.Previous = &motion.TempoSpec{Mode: "steady", LevelPercent: 25}
								}
								state.Outputs = &chat.OutputContext{Tempo: t}
							} else if outputs != "" {
								strokers := 1
								if outputs == "vibrator-only" {
									strokers = 0
								}
								state.Outputs = &chat.OutputContext{Strokers: strokers, Vibrator: &chat.VibrationContext{Independent: caps.AdvancedMotion, LevelControl: true, Ready: true, LevelPercent: 100, Follow: "speed", LevelFollowsHandy: true, Motors: 1}}
							}
							service := chat.Service{Provider: original, Model: "gpt-6.1-terra", Prompt: preset, Capabilities: &caps, MotionContext: &state, SingleAttempt: true}
							if caps.LocalInference {
								size := 32768
								if compact || (caps.SimpleMotion && !grouped) {
									size = 16384
								}
								if grouped && !compact {
									size = 131072
								}
								caps.ReplyLength = ""
								service.MaxTokens = 256
								service.PromptBudget = chat.PromptBudgetSettings{ContextSize: size, MaxOutputTokens: 256}
							}
							request := chat.Request{Message: "Mobile prompt assembly."}
							switch phase {
							case "plan", "plan-local", "speech", "talk":
								caps.AutoTiming = phase == "plan" && caps.AdvancedMotion
								caps.AutoTimingFloor = 8
								caps.Motion = phase != "talk"
								kind := chat.AutopilotKindMotion
								if phase != "plan" && phase != "plan-local" {
									kind = chat.AutopilotKindSpeech
								}
								planner := chat.AutopilotService{Provider: original, Prompt: preset, Model: "gpt-6.1-terra", Capabilities: caps, MotionContext: &state, MaxTokens: 1536}
								_, _ = planner.Complete(context.Background(), kind, request)
							default:
								_, _ = service.Complete(context.Background(), request, nil)
							}
							input := peer.input
							if len(input.Input) < 2 || len(input.Text.Format.Schema) == 0 {
								panic(fmt.Sprintf("missing contract %s %s", tier, mode))
							}
							schema := string(input.Text.Format.Schema)
							idx, ok := schemas[schema]
							if !ok {
								idx = len(bank.Schemas)
								schemas[schema] = idx
								bank.Schemas = append(bank.Schemas, input.Text.Format.Schema)
							}
							entry := template{Schema: idx}
							domain := string(original.schema)
							if _, ok := schemas[domain]; !ok {
								schemas[domain] = len(bank.Schemas)
								bank.Schemas = append(bank.Schemas, original.schema)
							}
							entry.Domain = schemas[domain]
							entry.Suffix = strings.TrimPrefix(input.Input[len(input.Input)-1].Content, request.Message)
							for _, line := range strings.Split(input.Input[0].Content, "\n") {
								i, ok := lines[line]
								if !ok {
									i = len(bank.Lines)
									lines[line] = i
									bank.Lines = append(bank.Lines, line)
								}
								entry.Lines = append(entry.Lines, i)
							}
							key := locale.tag + "/" + tier + "/" + string(mode) + "/" + phase
							if tempo {
								key = locale.tag + "/" + tier + "/tempo/" + phase + strings.TrimPrefix(outputs, "tempo")
							} else if outputs != "" {
								key += "/" + outputs
							}
							if compact {
								key += "/compact"
							}
							bank.Templates[key] = entry
						}

					}
				}
			}
		}
	}
	data, err := json.Marshal(bank)
	if err != nil {
		panic(err)
	}
	if err = os.WriteFile(*out, append(data, '\n'), 0644); err != nil {
		panic(err)
	}
	fmt.Printf("Wrote %d templates, %d shared lines, %d schemas.\n", len(bank.Templates), len(bank.Lines), len(bank.Schemas))
}
