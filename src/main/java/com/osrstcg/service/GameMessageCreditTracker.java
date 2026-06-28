package com.osrstcg.service;

import com.osrstcg.ui.TcgPanel;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Awards credits for activities that cannot use {@link NpcKillCreditTracker} (e.g. raid NPCs with no combat level).
 */
@Singleton
public final class GameMessageCreditTracker
{
	private static final List<CreditRule> CREDIT_RULES = List.of(
		CreditRule.prefix(
			"Your completed Chambers of Xeric count is:",
			12_500L,
			"Chambers of Xeric completion")
	);

	private final CreditAwardService creditAwardService;
	private final TcgPanel tcgPanel;

	@Inject
	GameMessageCreditTracker(CreditAwardService creditAwardService, TcgPanel tcgPanel)
	{
		this.creditAwardService = creditAwardService;
		this.tcgPanel = tcgPanel;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event == null || !ChatMessageType.GAMEMESSAGE.equals(event.getType()))
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		Optional<CreditRule> rule = firstMatchingRule(message);
		if (rule.isEmpty())
		{
			return;
		}

		CreditRule matched = rule.get();
		creditAwardService.awardFlatCredits(matched.getReason(), matched.getCredits());
		tcgPanel.refresh();
	}

	private static Optional<CreditRule> firstMatchingRule(String messageWithoutTags)
	{
		for (CreditRule rule : CREDIT_RULES)
		{
			if (rule.matches(messageWithoutTags))
			{
				return Optional.of(rule);
			}
		}
		return Optional.empty();
	}

	private static final class CreditRule
	{
		private final String messagePrefix;
		private final long credits;
		private final String reason;

		private CreditRule(String messagePrefix, long credits, String reason)
		{
			this.messagePrefix = messagePrefix;
			this.credits = credits;
			this.reason = reason;
		}

		static CreditRule prefix(String messagePrefix, long credits, String reason)
		{
			return new CreditRule(messagePrefix, credits, reason);
		}

		boolean matches(String message)
		{
			return message != null && message.startsWith(messagePrefix);
		}

		long getCredits()
		{
			return credits;
		}

		String getReason()
		{
			return reason;
		}
	}
}
