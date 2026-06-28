package com.osrstcg.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.osrstcg.ui.TcgPanel;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Awards kill credits from actual NPC deaths (including zero-loot kills), using the same engagement
 * signals as {@code monster-monitor}: player target + player damage within a short tick window.
 * Replaces credits tied to {@link net.runelite.client.plugins.loottracker.LootReceived}.
 */
@Singleton
public final class NpcKillCreditTracker
{
	private static final int INTERACTION_TIMEOUT_TICKS = 7;

	/** Boss display name -> NPC ids that count as the real kill (final phase only). */
	private static final Map<String, Set<Integer>> FINAL_PHASE_IDS = Map.ofEntries(
		Map.entry("Kalphite Queen", Set.of(965)),
		Map.entry("The Nightmare", Set.of(378)),
		Map.entry("Phosani's Nightmare", Set.of(377)),
		Map.entry("Alchemical Hydra", Set.of(8622)),
		Map.entry("Hydra", Set.of(8609)),
		Map.entry("Phantom Muspah", Set.of(12082)),
		Map.entry("Dusk", Set.of(7889)),
		Map.entry("Abyssal Sire", Set.of(5891)),
		Map.entry("Kephri", Set.of(11722)),
		Map.entry("Verzik Vitur", Set.of(10832, 8371, 10849))
	);

	/** Kill-credit exclusions: exact name, name fragment (with optional exception), or NPC id. */
	private static final NpcExclusionRule[] NPC_EXCLUSIONS = {
		NpcExclusionRule.exactName("Great Olm"),
		NpcExclusionRule.nameContains("nylocas", "Nylocas Vasilias"),
		NpcExclusionRule.npcIds(ExcludedNpcIds.HUEYCOATL_BODY_SEGMENTS),
		NpcExclusionRule.npcIds(ExcludedNpcIds.HUEYCOATL_TAIL),
		NpcExclusionRule.npcIds(ExcludedNpcIds.HUEYCOATL_BODY_AND_RUBBLE),
		NpcExclusionRule.npcIds(ExcludedNpcIds.GROTESQUE_GUARDIANS_DAWN),
		NpcExclusionRule.npcIds(ExcludedNpcIds.PHANTOM_MUSPAH_UNSTABLE_ICE),
		NpcExclusionRule.npcIds(ExcludedNpcIds.CRACKED_ICE),
		NpcExclusionRule.npcIds(ExcludedNpcIds.GREAT_OLM_RIGHT_CLAW),
		NpcExclusionRule.npcIds(ExcludedNpcIds.GREAT_OLM_LEFT_CLAW),
	};

	private final Client client;
	private final ClientThread clientThread;
	private final CreditAwardService creditAwardService;
	private final TcgPanel tcgPanel;
	private final SpecialNpcCreditWatch specialNpcCreditWatch;

	private final Map<Integer, String> lastKnownNpcName = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> lastInteractionTicks = new ConcurrentHashMap<>();
	private final Map<Integer, Boolean> wasNpcEngaged = new ConcurrentHashMap<>();

	@Inject
	public NpcKillCreditTracker(
		Client client,
		ClientThread clientThread,
		CreditAwardService creditAwardService,
		TcgPanel tcgPanel)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.creditAwardService = creditAwardService;
		this.tcgPanel = tcgPanel;
		this.specialNpcCreditWatch = new SpecialNpcCreditWatch(clientThread, creditAwardService, tcgPanel);
	}

	public void shutdown()
	{
		specialNpcCreditWatch.shutdown();
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		Actor source = event.getSource();
		Actor target = event.getTarget();

		if (source == client.getLocalPlayer() && target instanceof NPC)
		{
			NPC npc = (NPC) target;
			int npcIndex = npc.getIndex();
			String npcName = Optional.ofNullable(npc.getName()).orElse("Unnamed NPC");

			lastKnownNpcName.put(npcIndex, npcName);
			lastInteractionTicks.put(npcIndex, client.getTickCount());
			// Count targeting as engagement so one-hit kills still qualify if ActorDeath runs before HitsplatApplied.
			wasNpcEngaged.put(npcIndex, true);

			if (specialNpcCreditWatch.isSpecialNpc(npc))
			{
				specialNpcCreditWatch.trackNpc(npc);
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor target = event.getActor();
		Hitsplat hitsplat = event.getHitsplat();

		if (target instanceof NPC && hitsplat.isMine())
		{
			NPC npc = (NPC) target;
			int npcIndex = npc.getIndex();
			String npcName = Optional.ofNullable(npc.getName()).orElse(lastKnownNpcName.getOrDefault(npcIndex, "Unnamed NPC"));

			lastKnownNpcName.put(npcIndex, npcName);
			lastInteractionTicks.put(npcIndex, client.getTickCount());
			wasNpcEngaged.put(npcIndex, true);
			specialNpcCreditWatch.retrackAfterPlayerHit(npc);
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();

		if (!(actor instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) actor;
		int npcIndex = npc.getIndex();
		int npcId = npc.getId();
		String npcName = normalizeName(lastKnownNpcName.getOrDefault(npcIndex, npc.getName()));

		if (isExcludedNpc(npcName, npcId))
		{
			cleanupAfterLogging(npcIndex);
			return;
		}

		if (specialNpcCreditWatch.isSpecialNpc(npc))
		{
			cleanupAfterLogging(npcIndex);
			return;
		}

		if (FINAL_PHASE_IDS.containsKey(npcName))
		{
			Set<Integer> finalIds = FINAL_PHASE_IDS.get(npcName);
			if (!finalIds.contains(npcId))
			{
				cleanupAfterLogging(npcIndex);
				return;
			}
		}

		final int idx = npcIndex;
		final String awardName = npcName;
		final int combatLevel = npc.getCombatLevel();
		clientThread.invokeLater(() ->
		{
			try
			{
				if (Boolean.TRUE.equals(wasNpcEngaged.get(idx)) && isInteractionValid(idx))
				{
					creditAwardService.awardNpcKillCredits(awardName, combatLevel);
					tcgPanel.refresh();
				}
			}
			finally
			{
				cleanupAfterLogging(idx);
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int currentTick = client.getTickCount();
		specialNpcCreditWatch.updateTrackedNpcs();

		lastInteractionTicks.keySet().removeIf(npcIndex ->
			(currentTick - lastInteractionTicks.get(npcIndex)) > INTERACTION_TIMEOUT_TICKS);
	}

	private static String normalizeName(String npcName)
	{
		if (npcName == null)
		{
			return "Unnamed NPC";
		}
		return npcName.replaceAll("<.*?>", "").trim();
	}

	private boolean isExcludedNpc(String npcName, int npcId)
	{
		String normalized = normalizeName(npcName);
		for (NpcExclusionRule rule : NPC_EXCLUSIONS)
		{
			if (rule.matches(normalized, npcId))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isExcludedNpcName(String npcName)
	{
		String normalized = normalizeName(npcName);
		for (NpcExclusionRule rule : NPC_EXCLUSIONS)
		{
			if (rule.matchesName(normalized))
			{
				return true;
			}
		}
		return false;
	}

	private boolean isInteractionValid(int npcIndex)
	{
		Integer lastTick = lastInteractionTicks.get(npcIndex);
		return lastTick != null && (client.getTickCount() - lastTick) <= INTERACTION_TIMEOUT_TICKS;
	}

	private void cleanupAfterLogging(int npcIndex)
	{
		lastKnownNpcName.remove(npcIndex);
		lastInteractionTicks.remove(npcIndex);
		wasNpcEngaged.remove(npcIndex);
	}

	/**
	 * NPC ids for minions / non-kill phases (matches monster-monitor exclusions).
	 * Real boss kills are tracked via {@link #FINAL_PHASE_IDS}, {@link HealthTrackedNpcIds}, or
	 * {@link GameMessageCreditTracker}.
	 */
	private static final class ExcludedNpcIds
	{
		/** The Hueycoatl — exposed body segments before the summit fight. */
		static final Set<Integer> HUEYCOATL_BODY_SEGMENTS = Set.of(14010, 14011, 14013);

		/** The Hueycoatl — tail (including broken tail during shield phase). */
		static final Set<Integer> HUEYCOATL_TAIL = Set.of(14014, 14015);

		/** The Hueycoatl — coiled body parts and rubble on the mountain path. */
		static final Set<Integer> HUEYCOATL_BODY_AND_RUBBLE = Set.of(14017, 14018);

		/** Grotesque Guardians — Dawn phase ({@link #FINAL_PHASE_IDS} credits Dusk). */
		static final Set<Integer> GROTESQUE_GUARDIANS_DAWN = Set.of(7888);

		/** Amoxliatl — unstable ice spawned during the fight. */
		static final Set<Integer> PHANTOM_MUSPAH_UNSTABLE_ICE = Set.of(13688);

		/** Cracked Ice — Moons. */
		static final Set<Integer> CRACKED_ICE = Set.of(13026);

		/** Great Olm — right claw ({@link #NPC_EXCLUSIONS} exact-name rule covers the head). */
		static final Set<Integer> GREAT_OLM_RIGHT_CLAW = Set.of(7550, 7553);

		/** Great Olm — left claw. */
		static final Set<Integer> GREAT_OLM_LEFT_CLAW = Set.of(7552, 7555);

		private ExcludedNpcIds()
		{
		}
	}

	/**
	 * Boss NPC ids tracked via health ratio when {@link ActorDeath} does not fire (see monster-monitor).
	 */
	private static final class HealthTrackedNpcIds
	{
		/** The Hueycoatl — summit fight (earlier form; only {@link #HUEYCOATL_FINAL} awards credit). */
		static final int HUEYCOATL = 14009;

		/** The Hueycoatl — final kill form on the Darkfrost summit. */
		static final int HUEYCOATL_FINAL = 14012;

		/** Amoxliatl — Varlamore frost boss. */
		static final int AMOXLIATL = 13685;

		/** Duke Sucellus — DT2 boss. */
		static final int DUKE_SUCELLUS = 12166;

		/** Perilous Moons — Blood Moon. */
		static final int BLOOD_MOON = 13011;

		/** Perilous Moons — Eclipse Moon. */
		static final int ECLIPSE_MOON = 13012;

		/** Perilous Moons — Blue Moon. */
		static final int BLUE_MOON = 13013;

		static final Set<Integer> ALL = Set.of(
			HUEYCOATL,
			HUEYCOATL_FINAL,
			AMOXLIATL,
			DUKE_SUCELLUS,
			BLOOD_MOON,
			ECLIPSE_MOON,
			BLUE_MOON);

		private HealthTrackedNpcIds()
		{
		}
	}

	private static final class NpcExclusionRule
	{
		private enum MatchMode
		{
			EXACT_NAME,
			NAME_CONTAINS,
			NPC_ID
		}

		private final MatchMode mode;
		private final String pattern;
		private final String exceptName;
		private final Set<Integer> npcIds;

		private NpcExclusionRule(MatchMode mode, String pattern, String exceptName, Set<Integer> npcIds)
		{
			this.mode = mode;
			this.pattern = pattern;
			this.exceptName = exceptName;
			this.npcIds = npcIds;
		}

		static NpcExclusionRule exactName(String name)
		{
			return new NpcExclusionRule(MatchMode.EXACT_NAME, name, null, Set.of());
		}

		static NpcExclusionRule nameContains(String fragment, String exceptName)
		{
			return new NpcExclusionRule(MatchMode.NAME_CONTAINS, fragment, exceptName, Set.of());
		}

		static NpcExclusionRule npcIds(Set<Integer> ids)
		{
			return new NpcExclusionRule(MatchMode.NPC_ID, null, null, ids);
		}

		boolean matches(String normalizedName, int npcId)
		{
			switch (mode)
			{
				case EXACT_NAME:
					return matchesExactName(normalizedName);
				case NAME_CONTAINS:
					return matchesNameContains(normalizedName);
				case NPC_ID:
					return npcIds.contains(npcId);
				default:
					return false;
			}
		}

		boolean matchesName(String normalizedName)
		{
			switch (mode)
			{
				case EXACT_NAME:
					return matchesExactName(normalizedName);
				case NAME_CONTAINS:
					return matchesNameContains(normalizedName);
				default:
					return false;
			}
		}

		private boolean matchesExactName(String normalizedName)
		{
			return pattern.equalsIgnoreCase(normalizedName);
		}

		private boolean matchesNameContains(String normalizedName)
		{
			return normalizedName.toLowerCase(Locale.ENGLISH).contains(pattern.toLowerCase(Locale.ENGLISH))
				&& !exceptName.equalsIgnoreCase(normalizedName);
		}
	}

	/**
	 * NPCs that may not fire {@link ActorDeath}; death inferred from health ratio (see monster-monitor).
	 */
	private static final class SpecialNpcCreditWatch
	{
		private final Map<Integer, NPC> trackedNpcs = new ConcurrentHashMap<>();
		private final Set<Integer> loggedNpcIndices = ConcurrentHashMap.newKeySet();
		private final ClientThread clientThread;
		private final CreditAwardService creditAwardService;
		private final TcgPanel tcgPanel;

		private SpecialNpcCreditWatch(ClientThread clientThread, CreditAwardService creditAwardService, TcgPanel tcgPanel)
		{
			this.clientThread = clientThread;
			this.creditAwardService = creditAwardService;
			this.tcgPanel = tcgPanel;
		}

		boolean isSpecialNpc(NPC npc)
		{
			return npc != null && HealthTrackedNpcIds.ALL.contains(npc.getId());
		}

		void trackNpc(NPC npc)
		{
			if (isSpecialNpc(npc))
			{
				trackedNpcs.put(npc.getIndex(), npc);
			}
		}

		void updateTrackedNpcs()
		{
			clientThread.invokeLater(() ->
			{
				for (NPC npc : Set.copyOf(trackedNpcs.values()))
				{
					if (npc == null)
					{
						continue;
					}
					int healthRatio = npc.getHealthRatio();
					int npcIndex = npc.getIndex();

					if (healthRatio <= 1 && !loggedNpcIndices.contains(npcIndex) && shouldCredit(npc))
					{
						loggedNpcIndices.add(npcIndex);
						String name = normalizeName(npc.getName());
						if (isExcludedNpcName(name))
						{
							continue;
						}
						int combatLevel = npc.getCombatLevel();
						creditAwardService.awardNpcKillCredits(name, combatLevel);
						tcgPanel.refresh();
					}
					else if (healthRatio > 0 && loggedNpcIndices.contains(npcIndex))
					{
						loggedNpcIndices.remove(npcIndex);
						trackNpc(npc);
					}
				}
			});
		}

		private static boolean shouldCredit(NPC npc)
		{
			if ("The Hueycoatl".equalsIgnoreCase(npc.getName()))
			{
				return npc.getId() == HealthTrackedNpcIds.HUEYCOATL_FINAL;
			}
			return true;
		}

		private static String normalizeName(String npcName)
		{
			if (npcName == null)
			{
				return "Unnamed NPC";
			}
			return npcName.replaceAll("<.*?>", "").trim();
		}

		void retrackAfterPlayerHit(NPC npc)
		{
			if (npc == null)
			{
				return;
			}
			int npcIndex = npc.getIndex();
			if (loggedNpcIndices.contains(npcIndex))
			{
				loggedNpcIndices.remove(npcIndex);
				trackNpc(npc);
			}
		}

		void shutdown()
		{
			trackedNpcs.clear();
			loggedNpcIndices.clear();
		}
	}
}
