// Source: internal/chat/{continuous_history,turn_score}.go at 1b80d72e.
package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/mapledaemon/MagicHandy/internal/chat"
	"github.com/mapledaemon/MagicHandy/internal/llm"
)

// Use the real interactive composer and final provider budget, without an HTTP provider.
type promptRecorder struct{ request llm.ChatRequest }

func (r *promptRecorder) Status(context.Context) llm.ProviderStatus { return llm.ProviderStatus{} }
func (r *promptRecorder) StreamChat(_ context.Context, req llm.ChatRequest, _ func(string) error) (string, error) {
	r.request = req
	return `{"action":"none","edits":[],"reply":"Ready."}`, nil
}

func (e *exporter) localHistory() {
	for _, tc := range []struct {
		id                     string
		context, rows, repeats int
		managed                bool
	}{
		{"named-32k", 32768, chat.PromptHistoryLimit, 1, false},
		{"managed-32k", 32768, chat.ManagedLayeredHistoryLimit, 1, true},
		{"managed-16k", 16384, chat.PromptHistoryLimit, 1, true},
		{"named-32k-verbose", 32768, chat.PromptHistoryLimit, 88, false},
	} {
		var history []llm.Message
		state := chat.MotionContext{Running: true, MotionMode: chat.MotionModeLayered, SpeedMinPercent: 20, SpeedMaxPercent: 80}
		score := chat.DefaultLayeredScore(45)
		state.Layered = &score
		for i := 0; i < 55; i++ {
			text := fmt.Sprintf("Synthetic note %02d. ", i) + strings.Repeat("Archived display note. ", tc.repeats)
			history = append(history, llm.Message{Role: "user", Content: strings.TrimSpace(text)}, llm.Message{Role: "assistant", Content: "Noted."})
			if i >= 47 {
				state.UserRequests = append(state.UserRequests, strings.TrimSpace(text))
				state.UserRequestSecondsAgo = append(state.UserRequestSecondsAgo, 60-i)
			}
		}
		set, ok := chat.BuiltinPromptSetByID(chat.DefaultPromptSetID)
		if !ok {
			panic("missing default prompt set")
		}
		rec := new(promptRecorder)
		caps := chat.Capabilities{Motion: true, LocalInference: true, MotionMode: chat.MotionModeLayered, SimpleMotion: true, HistoryMessages: tc.rows, HistoryBytes: chat.PromptHistoryBytes}
		budget := chat.PromptBudgetSettings{ContextSize: tc.context, MaxOutputTokens: 256}
		service := chat.Service{Provider: rec, Prompt: set, Capabilities: &caps, MotionContext: &state, PromptBudget: budget, MaxTokens: 256}
		const message = "Read the latest note back."
		_, err := service.Complete(context.Background(), chat.Request{Message: message, History: history}, nil)
		must(err)
		admitted, measured, err := llm.BudgetChatRequest(rec.request, tc.context)
		must(err)
		id := "layered-history-" + tc.id
		rev := 1
		e.json(record{ID: id, Role: "local-history", Path: "chat/history/" + id + ".json", Mode: "layered", Capability: "simple", Locale: "en", Revision: &rev}, map[string]any{
			"context_tokens": tc.context, "managed": tc.managed, "repetitions": tc.repeats, "input_rows": len(history),
			"row_limit": tc.rows, "message": message, "contract": admitted.Messages[0].Content, "schema_bytes": len(admitted.JSONSchema),
			"messages": admitted.Messages[1:], "budget": measured,
		})
	}
}
