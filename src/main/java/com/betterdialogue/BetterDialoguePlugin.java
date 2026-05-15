/*
 * Copyright (c) 2026, theOranguzang
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.betterdialogue;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.ClientTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

/**
 * Dialogue Fonts — replaces the hard-to-read OSRS bitmap cursive (Quill 8)
 * with a clean, configurable TrueType font rendered through RuneLite's
 * {@link net.runelite.client.ui.overlay.Overlay} system.
 *
 * <h3>Rendering pipeline (per game tick)</h3>
 * <pre>
 * onGameTick
 *   └─ DialogueWidgetManager.getCurrentDialogue()
 *       ├─ detect visible dialogue widget
 *       ├─ extract + parse text
 *       ├─ blank original widget text (setText(""))
 *       └─ return DialogueState snapshot
 *
 * BetterDialogueOverlay.render(Graphics2D)
 *   ├─ read DialogueState
 *   ├─ fill background over text area
 *   └─ draw replacement text (word-wrapped, colour-tagged)
 * </pre>
 *
 * <h3>Text hiding strategy</h3>
 * This plugin uses <em>Option A</em> from the blueprint: calling
 * {@link net.runelite.api.widgets.Widget#setText(String) Widget.setText("")}
 * each tick on the text children only.  The widget's click handler is bound to
 * widget bounds, not text content, so "Click here to continue" and option
 * selection remain fully functional.  Original text is restored in
 * {@link #shutDown()} via {@link DialogueWidgetManager#restoreAll()}.
 */
@Slf4j
@PluginDescriptor(
	name = "Dialogue Fonts",
	description = "Replaces the OSRS dialogue font with a clean, readable TrueType font",
	tags = {"dialogue", "font", "accessibility", "text", "npc", "chat", "readable"}
)
public class BetterDialoguePlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BetterDialogueOverlay overlay;

	@Inject
	private DialogueWidgetManager widgetManager;

	// -------------------------------------------------------------------------
	// Lifecycle
	// -------------------------------------------------------------------------

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		log.debug("Dialogue Fonts started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		// Restore any widget text we blanked so nothing is left broken
		widgetManager.restoreAll();
		// Clear the overlay state so stale text isn't painted after re-enable
		overlay.setState(null);
		log.debug("Dialogue Fonts stopped");
	}

	// -------------------------------------------------------------------------
	// Events
	// -------------------------------------------------------------------------

	/**
	 * Fires every client frame (~50/s), not just every server tick (~1.67/s).
	 * Using ClientTick instead of GameTick prevents the one-frame flash of the
	 * original bitmap font that would occur when a dialogue first opens and the
	 * game sets the widget text before the next 600 ms game tick would fire.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		DialogueState state = widgetManager.getCurrentDialogue();
		overlay.setState(state);
	}

	// -------------------------------------------------------------------------
	// Config
	// -------------------------------------------------------------------------

	@Provides
	BetterDialogueConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BetterDialogueConfig.class);
	}
}

