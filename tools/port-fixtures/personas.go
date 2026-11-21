// Independent end-to-end captures. The only HTTP client has an in-process RoundTripper.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/mapledaemon/MagicHandy/internal/chat"
	"github.com/mapledaemon/MagicHandy/internal/config"
	"github.com/mapledaemon/MagicHandy/internal/llm"
)

type personaCase struct {
	Voice       chat.VoiceLevel    `json:"voice"`
	Style       chat.ReactionStyle `json:"style"`
	Name        string             `json:"name"`
	Description string             `json:"description"`
	Detailed    bool               `json:"detailed"`
	Kind        string             `json:"kind"`
}

func (e *exporter) personas() {
	for _, lang := range []struct{ tag, id string }{
		{"en", chat.DefaultPromptSetID}, {"es", chat.PromptSetIDSpanish}, {"pt-BR", chat.PromptSetIDPortugueseBrazil},
		{"ja", chat.PromptSetIDJapanese}, {"zh-Hans", chat.PromptSetIDSimplifiedChinese},
	} {
		set, _ := chat.BuiltinPromptSetByID(lang.id)
		export := func(c personaCase) { e.personaRequest(lang.tag, set, c) }
		// A literal slot-like word, quotes and markup exercise the quoting boundary.
		const desc = "Quick-witted, with a \"quoted\" <example> & MobilePersonaName."
		for _, level := range []chat.VoiceLevel{chat.VoiceUtility, chat.VoiceWarm, chat.VoiceIntimate, chat.VoiceExplicit} {
			export(personaCase{level, chat.StyleNeutral, "Kit", desc, false, "hosted"})
		}
		for _, style := range []chat.ReactionStyle{chat.StylePlayful, chat.StyleTender, chat.StyleTeasing, chat.StyleDominant, chat.StyleSubmissive} {
			export(personaCase{chat.VoiceWarm, style, "Kit", desc, false, "hosted"})
		}
		export(personaCase{chat.VoiceIntimate, chat.StyleTender, "", desc, true, "hosted"})
		export(personaCase{chat.VoiceExplicit, chat.StyleDominant, "Kit", desc, true, "simple"})
		export(personaCase{chat.VoiceWarm, chat.StylePlayful, "Kit", "", false, "intermediate"})
		export(personaCase{chat.VoiceIntimate, chat.StyleNeutral, "Kit", desc, true, "talk"})
		export(personaCase{chat.VoiceWarm, chat.StyleNeutral, "Kit", desc, false, "speech"})
	}
}

func (e *exporter) personaRequest(tag string, set chat.PromptSet, c personaCase) {
	local := c.Kind == "simple" || c.Kind == "intermediate"
	speech := c.Kind == "speech" || c.Kind == "talk"
	mode := chat.MotionModeLayered
	if c.Kind == "hosted" {
		mode = chat.MotionModeOff
	}
	caps := chat.Capabilities{Voice: c.Voice, Style: c.Style, MotionMode: mode, Motion: mode != chat.MotionModeOff && c.Kind != "talk",
		LocalInference: local, AdvancedMotion: !local, SimpleMotion: c.Kind == "simple", IntermediateMotion: c.Kind == "intermediate",
		PreserveConversationText: !local, HistoryMessages: 16, HistoryBytes: 8192}
	if c.Detailed {
		caps.ReplyLength = chat.ReplyLengthDetailed
	}
	profile := &chat.ConversationContext{PersonaName: c.Name, PersonaDescription: c.Description}
	state := chat.MotionContext{SpeedMinPercent: 10, SpeedMaxPercent: 43, Running: speech}
	if speech {
		score := chat.DefaultLayeredScore(25)
		state.Layered = &score
	}
	const tokens = 131072
	output := 1536
	if local {
		output = (config.LLMSettings{MaxOutputTokens: 256}).ChatMaxOutputTokens(string(caps.ReplyLength))
	}
	wire := new(capture)
	var peer llm.Provider
	rec := new(promptRecorder)
	if local {
		peer = rec
	} else {
		p, err := llm.NewOpenAIResponsesProvider(llm.OpenAIOptions{PlanUsage: true, Model: "gpt-6.1-terra", ReasoningEffort: "low", ContextSize: tokens, BaseURL: "https://offline.invalid", Client: &http.Client{Transport: wire}, Token: func(context.Context) (string, error) { return "synthetic-fixture", nil }})
		must(err)
		peer = p
	}
	request := chat.Request{Message: "A synthetic turn."}
	if speech {
		svc := chat.AutopilotService{Provider: peer, Model: "gpt-6.1-terra", Prompt: set, Capabilities: caps, MotionContext: &state, ConversationContext: profile, MaxTokens: output}
		_, _ = svc.Complete(context.Background(), chat.AutopilotKindSpeech, request)
	} else {
		svc := chat.Service{Provider: peer, Model: "gpt-6.1-terra", Prompt: set, Capabilities: &caps, MotionContext: &state, ConversationContext: profile, SingleAttempt: true, MaxTokens: output, PromptBudget: chat.PromptBudgetSettings{ContextSize: tokens, MaxOutputTokens: output}}
		_, _ = svc.Complete(context.Background(), request, nil)
	}
	result := map[string]any{"persona": c, "user": request.Message, "locale": tag, "output_tokens": output}
	if local {
		if len(rec.request.Messages) < 2 {
			panic("missing local persona capture")
		}
		result["messages"] = rec.request.Messages
		result["schema"] = json.RawMessage(rec.request.JSONSchema)
		if rec.request.MaxTokens != output {
			panic("unexpected local output allowance")
		}
	} else {
		if len(wire.body) == 0 {
			panic("missing hosted persona capture")
		}
		result["body"] = wire.body
	}
	id := fmt.Sprintf("persona-%s-%s-%s-%s", tag, c.Kind, c.Voice, c.Style)
	if c.Detailed {
		id += "-detailed"
	}
	rev := 1
	e.json(record{ID: id, Role: "persona-request", Locale: tag, Capability: strings.ToLower(c.Kind), Mode: string(mode), Revision: &rev, Path: "chat/personas/" + id + ".json"}, result)
}
