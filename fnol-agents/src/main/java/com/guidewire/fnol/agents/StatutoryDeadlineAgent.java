package com.guidewire.fnol.agents;
import com.guidewire.fnol.common.Models.*;import org.springframework.stereotype.*;import java.time.*;import java.util.*;
interface DeadlineRuleProvider{ DeadlineResult deadline(String state, LocalDate incidentDate); }
@Component class SyntheticDeadlineRuleProvider implements DeadlineRuleProvider{ private static final Map<String,Integer> DAYS=Map.of("CA",30,"NY",35,"TX",15,"FL",20,"IL",30); public DeadlineResult deadline(String s,LocalDate d){ return new DeadlineResult(s,d.plusDays(DAYS.getOrDefault(s,30)),"MOCK_RULE","Synthetic prototype deadline; not legal advice."); } }
@Component public class StatutoryDeadlineAgent implements FNOLAgent<FNOLPayload,DeadlineResult>{ private final DeadlineRuleProvider p; public StatutoryDeadlineAgent(DeadlineRuleProvider p){this.p=p;} public DeadlineResult execute(FNOLPayload i){return p.deadline(i.state(),i.incidentDate());}}
