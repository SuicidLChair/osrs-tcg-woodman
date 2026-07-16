package com.osrstcg.overlay;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.service.CreditsRateTracker;
import com.osrstcg.service.TcgStateService;
import com.osrstcg.util.NumberFormatting;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Movable plain-text overlay for current credits and a short-window credits/h rate.
 */
@Singleton
public class CreditsInfoboxOverlay extends OverlayPanel
{
	private final OsrsTcgConfig config;
	private final TcgStateService stateService;
	private final CreditsRateTracker creditsRateTracker;

	@Inject
	CreditsInfoboxOverlay(
		OsrsTcgConfig config,
		TcgStateService stateService,
		CreditsRateTracker creditsRateTracker)
	{
		this.config = config;
		this.stateService = stateService;
		this.creditsRateTracker = creditsRateTracker;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.creditsInfobox())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Credits:")
			.right(NumberFormatting.format(stateService.getCredits()))
			.build());

		Long creditsPerHour = creditsRateTracker.creditsPerHourOrNull();
		if (creditsPerHour != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Credits/h:")
				.right(NumberFormatting.format(creditsPerHour))
				.build());
		}

		return super.render(graphics);
	}
}
