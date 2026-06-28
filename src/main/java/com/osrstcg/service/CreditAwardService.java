package com.osrstcg.service;

import com.osrstcg.util.NumberFormatting;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;

@Singleton
@Slf4j
public class CreditAwardService
{
	private static final long XP_PER_CREDIT_CHUNK = 1000L;
	private static final long CREDITS_PER_CHUNK = 100L;
	/** Ignore bogus fake XP drop payloads. */
	private static final int FAKE_XP_DROP_SANITY_CAP = 20_000_000;

	private final Client client;
	private final TcgStateService stateService;
	private final Map<Skill, Integer> lastKnownRealLevels = new EnumMap<>(Skill.class);
	private final int[] previousSkillXp = new int[Skill.values().length];
	private boolean skillLevelsInitialized;
	private boolean skillXpInitialized;

	/** XP from skill drops not yet converted into credit chunks. */
	private long uncreditedXp;

	@Inject
	public CreditAwardService(Client client, TcgStateService stateService)
	{
		this.client = client;
		this.stateService = stateService;
	}

	/**
	 * Call when the RuneScape profile (or persisted plugin state) changes so we do not compare XP across characters.
	 */
	public void resetExperienceCreditBaseline()
	{
		uncreditedXp = 0L;
		skillXpInitialized = false;
		Arrays.fill(previousSkillXp, 0);
		snapshotSkillExperiencesIfLoggedIn();
	}

	public void awardNpcKillCredits(String npcName, int combatLevel)
	{
		if (combatLevel <= 0)
		{
			return;
		}

		int creditsPerKill = applyKillCreditTuning(combatLevel);
		long totalCredits = creditsPerKill;
		if (totalCredits <= 0)
		{
			return;
		}

		stateService.addCredits(totalCredits);
		debugAward(String.format("Killed %s (lvl %d) -> +%s credits (total %s)",
			safeName(npcName), combatLevel, NumberFormatting.format(totalCredits), NumberFormatting.format(stateService.getCredits())));
	}

	public void awardFlatCredits(String reason, long credits)
	{
		if (credits <= 0L)
		{
			return;
		}

		stateService.addCredits(credits);
		debugAward(String.format("%s -> +%s credits (total %s)",
			safeName(reason), NumberFormatting.format(credits), NumberFormatting.format(stateService.getCredits())));
	}

	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null)
		{
			return;
		}

		trackXpGainFromStatChanged(skill, event.getXp());

		if (isOverallSkill(skill))
		{
			return;
		}

		int current = clampLevel(client.getRealSkillLevel(skill));
		if (!skillLevelsInitialized || !lastKnownRealLevels.containsKey(skill))
		{
			lastKnownRealLevels.put(skill, current);
			return;
		}

		int previous = lastKnownRealLevels.get(skill);

		if (current <= previous)
		{
			lastKnownRealLevels.put(skill, current);
			return;
		}

		long totalReward = 0L;
		double levelMult = Math.max(0.0d, stateService.getState().getRewardTuning().getLevelUpCreditMultiplier());
		for (int level = previous + 1; level <= current; level++)
		{
			totalReward += Math.round(levelUpReward(level) * levelMult);
		}

		lastKnownRealLevels.put(skill, current);
		if (totalReward > 0)
		{
			stateService.addCredits(totalReward);
			debugAward(String.format("Level up %s: %d -> %d -> +%s credits (total %s)",
				skill.getName(), previous, current, NumberFormatting.format(totalReward), NumberFormatting.format(stateService.getCredits())));
		}
	}

	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (event == null || event.getSkill() == null)
		{
			return;
		}

		int xp = event.getXp();
		if (xp <= 0 || xp >= FAKE_XP_DROP_SANITY_CAP)
		{
			return;
		}

		applyXpGain(xp, event.getSkill().getName() + " drop");
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			lastKnownRealLevels.clear();
			skillLevelsInitialized = true;
			snapshotSkillExperiencesIfLoggedIn();
			return;
		}

		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			lastKnownRealLevels.clear();
			skillLevelsInitialized = false;
			skillXpInitialized = false;
			uncreditedXp = 0L;
			Arrays.fill(previousSkillXp, 0);
		}
	}

	private void trackXpGainFromStatChanged(Skill skill, int currentXp)
	{
		if (isOverallSkill(skill))
		{
			return;
		}

		int skillIndex = skill.ordinal();
		if (skillIndex < 0 || skillIndex >= previousSkillXp.length)
		{
			return;
		}

		int previousXp = previousSkillXp[skillIndex];
		if (skillXpInitialized && previousXp > 0 && currentXp > previousXp)
		{
			applyXpGain(currentXp - previousXp, skill.getName());
		}
		previousSkillXp[skillIndex] = currentXp;
	}

	private void applyXpGain(long xpGained, String source)
	{
		if (xpGained <= 0L)
		{
			return;
		}

		uncreditedXp += xpGained;
		awardCreditsFromUncreditedXp(source);
	}

	/** @return true if credits were added from XP chunks */
	private boolean awardCreditsFromUncreditedXp(String source)
	{
		long chunks = uncreditedXp / XP_PER_CREDIT_CHUNK;
		if (chunks <= 0L)
		{
			return false;
		}

		double mult = Math.max(0.0d, stateService.getState().getRewardTuning().getXpCreditMultiplier());
		long credits = Math.round((double) (chunks * CREDITS_PER_CHUNK) * mult);
		long xpCredited = chunks * XP_PER_CREDIT_CHUNK;
		if (credits <= 0L)
		{
			uncreditedXp -= xpCredited;
			return false;
		}

		stateService.addCredits(credits);
		uncreditedXp -= xpCredited;
		debugAward(String.format("XP drop +%s (%s, chunks of %s) -> +%s credits (total %s)",
			NumberFormatting.format(xpCredited), safeName(source), NumberFormatting.format(XP_PER_CREDIT_CHUNK),
			NumberFormatting.format(credits), NumberFormatting.format(stateService.getCredits())));
		return true;
	}

	private void snapshotSkillExperiencesIfLoggedIn()
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int[] experiences = client.getSkillExperiences();
		System.arraycopy(experiences, 0, previousSkillXp, 0, Math.min(experiences.length, previousSkillXp.length));
		skillXpInitialized = true;
	}

	private int applyKillCreditTuning(int baseLevel)
	{
		double scaled = baseLevel * stateService.getState().getRewardTuning().getKillCreditMultiplier();
		return Math.max(0, (int) Math.round(scaled));
	}

	private int levelUpReward(int level)
	{
		int clamped = clampLevel(level);
		double reward = 100.0d + (clamped - 1) * (25000.0d - 100.0d) / 98.0d;
		return (int) Math.round(reward);
	}

	private int clampLevel(int level)
	{
		if (level < 1)
		{
			return 1;
		}
		return Math.min(level, 99);
	}

	private String safeName(String name)
	{
		return name == null || name.isEmpty() ? "Unknown NPC" : name;
	}

	private void debugAward(String message)
	{
		if (!stateService.isDebugLogging())
		{
			return;
		}

		log.info("[OSRS TCG] {}", message);
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[OSRS TCG] " + message, null);
	}

	private boolean isOverallSkill(Skill skill)
	{
		return skill != null && "Overall".equalsIgnoreCase(skill.getName());
	}
}
