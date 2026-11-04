import net.barttolo.bots.Guide;
public class GuideChecks {
 public static void main(String[] args){
  Guide g=Guide.defaults();g.validate();
  check(g.addressed("Asistente, ¿cómo consigo B-Coins?"),"mention with punctuation");
  check(!g.addressed("Voy a consultar a mi asistentevirtual"),"whole word mention");
  check(g.answer("Asistente, explícame las MISIONES").contains("oleadas"),"accented Spanish mission question");
  check(!g.topics.containsKey("tiempos"),"no timing topic");
  check(g.answer("eventos").contains("Cada semana")&&!g.answer("eventos").contains("Halloween"),"weekly spoiler-free events");
  check(g.answer("dc").contains("https://discord.gg/xBWHDmKwc"),"DC alias");
  check(g.answer("reglas").contains("Xray")&&g.answer("reglas").contains("ventajas injustas"),"anti-cheat rules");
  Guide legacy=new Guide();legacy.topics.putAll(g.topics);legacy.topics.put("tiempos",g.topics.get("eventos"));legacy.chatRadius=40;
  check(legacy.migrate()&&!legacy.topics.containsKey("tiempos")&&legacy.chatRadius==40,"legacy migration preserves preferences");
  check(!legacy.migrate(),"migration idempotence");
  check(g.answer("quiero saber las reglas").contains("robar"),"rules");
  check(g.answer("discord").contains("https://discord.gg/xBWHDmKwc"),"exact supplied Discord");
  check(g.answer("abcxyz").contains("No tengo una respuesta"),"honest unknown question");
  check(g.expand("Hola {jugador}, soy {bot}","Chris").equals("Hola Chris, soy Asistente"),"templates");
  g.cooldownSeconds=0;boolean rejected=false;try{g.validate();}catch(IllegalArgumentException e){rejected=true;}check(rejected,"reject unsafe config");
  System.out.println("14 guide checks passed");
 }
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
