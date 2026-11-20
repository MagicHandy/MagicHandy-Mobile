package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/mapledaemon/MagicHandy/internal/chat"
	"github.com/mapledaemon/MagicHandy/internal/llm"
)

// Capture the final request after the real chat composer, budgeter and strict-schema conversion.
// This RoundTripper has no fallback and cannot open a socket.
type capture struct{ body json.RawMessage }

func (c *capture) RoundTrip(req *http.Request) (*http.Response, error) {
	if c.body != nil || req.Method != "POST" || req.URL.Path != "/responses" {
		return nil, errors.New("unexpected fixture request")
	}
	defer req.Body.Close()
	var err error
	c.body, err = io.ReadAll(req.Body)
	if err != nil {
		return nil, err
	}
	return &http.Response{StatusCode: 403, Body: io.NopCloser(strings.NewReader("{}")), Header: make(http.Header)}, nil
}

func (e *exporter) hosted() {
	for _, tc := range []struct {
		mode                 chat.MotionMode
		locale, prompt, text string
	}{
		{chat.MotionModeLayered, "en", chat.DefaultPromptSetID, "Please start a slow, steady rhythm."},
		{chat.MotionModeCreativeV2, "es", chat.PromptSetIDSpanish, "Empieza con un ritmo suave."},
		{chat.MotionModeOff, "ja", chat.PromptSetIDJapanese, "こんにちは。"},
	} {
		wire := new(capture)
		provider, err := llm.NewOpenAIResponsesProvider(llm.OpenAIOptions{
			PlanUsage: true, Model: "gpt-6.1-terra", ReasoningEffort: "low",
			ContextSize: 131072, BaseURL: "https://fixture.invalid", Client: &http.Client{Transport: wire},
			Token: func(context.Context) (string, error) { return "synthetic-fixture", nil },
		})
		must(err)
		set, ok := chat.BuiltinPromptSetByID(tc.prompt)
		if !ok {
			panic("missing prompt set")
		}
		caps := chat.Capabilities{AdvancedMotion: true, PreserveConversationText: true, Motion: tc.mode != chat.MotionModeOff, MotionMode: tc.mode, Voice: chat.VoiceUtility, ReplyLength: chat.ReplyLengthBalanced, HistoryMessages: 16, HistoryBytes: 8192}
		svc := chat.Service{Provider: provider, Model: "gpt-6.1-terra", Prompt: set, Capabilities: &caps, MotionContext: &chat.MotionContext{SpeedMinPercent: 10, SpeedMaxPercent: 43}, SingleAttempt: true}
		_, err = svc.Complete(context.Background(), chat.Request{Message: tc.text}, nil)
		var rejected *llm.CloudError
		if !errors.As(err, &rejected) || rejected.Kind != "permission" || len(wire.body) == 0 {
			panic(fmt.Sprintf("request capture failed: %v", err))
		}
		id := "terra-" + string(tc.mode) + "-" + tc.locale
		rev := 1
		e.bytes(record{ID: id, Role: "chat", Locale: tc.locale, Capability: "advanced", Mode: string(tc.mode), Revision: &rev, Path: "chat/" + id + ".json"}, append(wire.body, '\n'))
	}
}
