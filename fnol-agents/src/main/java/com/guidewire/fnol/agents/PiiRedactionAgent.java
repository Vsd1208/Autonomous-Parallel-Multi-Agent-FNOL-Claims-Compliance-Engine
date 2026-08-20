package com.guidewire.fnol.agents;
import com.guidewire.fnol.common.Models.*;import org.springframework.stereotype.*;import java.util.*;import java.util.regex.*;
@Component public class PiiRedactionAgent implements FNOLAgent<FNOLPayload,PIIResult>{
  private static final Pattern SSN=Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"), DOB=Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b");
  public PIIResult execute(FNOLPayload p){ String text=Objects.toString(p.description(),""); List<String> types=new ArrayList<>(); if(SSN.matcher(text).find()||p.claimantSsn()!=null){types.add("SSN"); text=SSN.matcher(text).replaceAll("***-**-****");} if(DOB.matcher(text).find()||p.claimantDob()!=null){types.add("DOB"); text=DOB.matcher(text).replaceAll("**/**/****");} if(p.claimantName()!=null&&!p.claimantName().isBlank()){types.add("FULL_NAME"); text=text.replace(p.claimantName(),"[REDACTED_NAME]");} return new PIIResult(text,types,!types.isEmpty()); }
}
