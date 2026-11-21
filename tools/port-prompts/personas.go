// Export code-owned sections separately so another voice does not multiply the motion templates.
package main

import (
	"encoding/json"
	"os"
	"strings"

	"github.com/mapledaemon/MagicHandy/internal/chat"
)

const nameSlot = "MobilePersonaName"
const descriptionSlot = "MobilePersonaDescription"

type voice struct {
	Identity string `json:"identity"`
	Check    string `json:"check"`
}

type vocabulary struct {
	IdentityHeading string                        `json:"identityHeading"`
	CheckHeading    string                        `json:"checkHeading"`
	Voices          map[chat.VoiceLevel]voice     `json:"voices"`
	Styles          map[chat.ReactionStyle]string `json:"styles"`
	Profiles        map[string]string             `json:"profiles"`
	Detailed        string                        `json:"detailed"`
}

func writePersonas(output, deltaPath string) {
	data, err := os.ReadFile(deltaPath)
	if err != nil {
		panic(err)
	}
	var edits map[string]map[string]string
	if err := json.Unmarshal(data, &edits); err != nil {
		panic(err)
	}
	result := make(map[string]vocabulary)
	for tag, id := range map[string]string{"en": chat.DefaultPromptSetID, "es": chat.PromptSetIDSpanish, "ja": chat.PromptSetIDJapanese, "pt-BR": chat.PromptSetIDPortugueseBrazil, "zh-Hans": chat.PromptSetIDSimplifiedChinese} {
		set, ok := chat.BuiltinPromptSetByID(id)
		if !ok {
			panic(id)
		}
		sections := func(level chat.VoiceLevel, style chat.ReactionStyle, profile *chat.ConversationContext) map[string]string {
			caps := chat.Capabilities{Voice: level, Style: style, ReplyLength: chat.ReplyLengthDetailed}
			out := map[string]string{}
			for _, s := range chat.ComposePrompt(set, nil, nil, caps, nil, profile).Sections {
				out[s.ID] = s.Text
			}
			return out
		}
		v := vocabulary{Voices: map[chat.VoiceLevel]voice{}, Styles: map[chat.ReactionStyle]string{}, Profiles: map[string]string{}}
		for _, level := range []chat.VoiceLevel{chat.VoiceUtility, chat.VoiceWarm, chat.VoiceIntimate, chat.VoiceExplicit} {
			s := sections(level, chat.StyleNeutral, nil)
			identity, check := s["voice_identity"], s["voice_check"]
			if level == chat.VoiceUtility {
				v.IdentityHeading, _, _ = strings.Cut(identity, "\n")
				v.CheckHeading, _, _ = strings.Cut(check, "\n")
				v.Detailed = s["reply_length"]
				continue
			}
			for before, after := range edits[tag] {
				identity = strings.ReplaceAll(identity, before, after)
				check = strings.ReplaceAll(check, before, after)
			}
			v.Voices[level] = voice{identity, check}
		}
		for _, style := range []chat.ReactionStyle{chat.StyleNeutral, chat.StyleTender, chat.StylePlayful, chat.StyleTeasing, chat.StyleDominant, chat.StyleSubmissive} {
			v.Styles[style] = sections(chat.VoiceWarm, style, nil)["reaction_style"]
		}
		for key, profile := range map[string]*chat.ConversationContext{
			"name":        {PersonaName: nameSlot},
			"description": {PersonaDescription: descriptionSlot},
			"both":        {PersonaName: nameSlot, PersonaDescription: descriptionSlot},
		} {
			v.Profiles[key] = sections(chat.VoiceIntimate, chat.StyleNeutral, profile)["conversation_context"]
		}
		result[tag] = v
	}
	data, err = json.Marshal(result)
	if err != nil {
		panic(err)
	}
	if err := os.WriteFile(output, append(data, '\n'), 0644); err != nil {
		panic(err)
	}
}
