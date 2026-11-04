package net.barttolo.bots;

import java.text.Normalizer;
import java.util.*;

/** Pure conversation matching. No LLM, command execution, or player-chat recording. */
public final class Guide {
    public int schemaVersion=1;
    public String name="Asistente";
    public int chatRadius=32, visualRadius=64, cooldownSeconds=3, welcomeDelaySeconds=3;
    public boolean showInTab=true;
    public String firstWelcome="¡Bienvenido a RingWorld, {jugador}! Soy {bot}, el ayudante automático del spawn. Este mundo combina supervivencia, armas, misiones y eventos.";
    public String returnWelcome="¡Hola de nuevo, {jugador}! Soy {bot}, el bot del spawn. Si necesitás orientación, escribí /asistente.";
    public Map<String,Topic> topics=new LinkedHashMap<>();
    public static final class Topic {
        public List<String> aliases=new ArrayList<>();
        public String answer="";
        Topic(){}
        Topic(String text,String... words){answer=text;aliases=List.of(words);}
    }
    public static Guide defaults(){
        Guide g=new Guide();g.schemaVersion=2;
        g.topics.put("inicio",new Topic("RingWorld es un mundo en forma de anillo: supervivencia con mods, armas, misiones y eventos. Podés explorar, construir y jugar con otros. Temas: misiones, eventos, monedas, reglas, Discord y ayuda.","inicio","tematica","servidor","ringworld","de que trata","como jugar"));
        g.topics.put("misiones",new Topic("Las misiones son desafíos de combate con oleadas de enemigos y progresión por sectores del anillo. Prepará equipo y munición antes de entrar. Consultá los requisitos en el acceso de cada misión; para orientación escribí /asistente ayuda.","misiones","mision","oleadas","hangar"));
        g.topics.put("eventos",new Topic("Cada semana realizamos un evento con una temática distinta. Explorá, participá y conseguí B-Coins para canjear por recompensas. Las novedades se anuncian dentro del servidor y en Discord. ¡Descubrí las sorpresas jugando!","eventos","evento","semanal","semanales"));
        g.topics.put("monedas",new Topic("La moneda del servidor es B-Coin. Los eventos son una forma de conseguirla; podés canjearla por recompensas en la tienda o el casino. Mirá los precios actuales dentro del juego. No entrego monedas ni premios.","monedas","moneda","b coin","bcoin","b coins","bcoins","recompensas","tienda","casino","premios"));
        g.topics.put("armas",new Topic("El servidor usa armas de TACZ. Buscá sus recetas y las de munición en JEI: el crafteo forma parte de la progresión. Prepará equipo antes de las misiones.","armas","arma","tacz","municion","balas","crafteo","jei"));
        g.topics.put("reglas",new Topic("Cuidemos una comunidad justa: está prohibido robar, matar a otros jugadores, acosar o discriminar y dañar construcciones ajenas. No se permiten hacks, Xray, clientes modificados para hacer trampas, duplicación de objetos, abuso de errores ni herramientas o métodos que den ventajas injustas o perjudiquen la jugabilidad. Si encontrás un fallo, avisá a la administración y no lo aproveches.","reglas","normas","robar","pvp","hacks","hack","xray","trampas","duplicacion"));
        g.topics.put("discord",new Topic("Comunidad y ayuda de RingWorld: https://discord.gg/xBWHDmKwc", "discord","dc","comunidad","enlace"));
        g.topics.put("saludo",new Topic("¡Hola, {jugador}! Soy {bot}, el ayudante automático del spawn. Puedo orientarte sobre misiones, eventos, monedas y reglas. Escribí /asistente para ver los temas.","hola","buenas","buenos dias","saludos"));
        g.topics.put("identidad",new Topic("Soy {bot}, un bot de ayuda de RingWorld. Tengo respuestas programadas: no soy una persona ni una IA de conversación libre. Mi tarea es darte la bienvenida y orientarte.","quien eres","quien sos","eres un bot","sos un bot","que haces","que estas haciendo"));
        g.topics.put("ayuda",new Topic("Usá /asistente misiones, /asistente eventos, /asistente monedas, /asistente reglas o /asistente discord. También podés mencionarme como Asistente cerca del spawn. Si tu duda no está cubierta, pedí ayuda en Discord.","ayuda","ayudame","comandos","no entiendo"));
        return g;
    }
    public boolean migrate(){
        if(schemaVersion>=2)return false;
        Guide next=defaults();
        if(topics==null)topics=new LinkedHashMap<>();
        topics.remove("tiempos");
        for(String key:List.of("inicio","eventos","reglas","discord","ayuda"))topics.put(key,next.topics.get(key));
        name="Asistente";schemaVersion=2;return true;
    }
    public static String normalize(String input){
        return Normalizer.normalize(input,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]"," ").replaceAll("\\s+"," ").trim();
    }
    public boolean addressed(String text){
        String value=" "+normalize(text)+" ";
        return value.contains(" asistente ")||value.contains(" asistente bot ")||value.contains(" "+normalize(name)+" ");
    }
    public String answer(String input){
        String n=" "+normalize(input)+" ";Topic best=null;int length=0;
        for(var entry:topics.entrySet())for(String alias:entry.getValue().aliases){
            String key=normalize(alias);
            if(!key.isEmpty()&&key.length()>length&&n.contains(" "+key+" ")){best=entry.getValue();length=key.length();}
        }
        return best==null?"No tengo una respuesta preparada para esa pregunta. Probá /asistente ayuda o consultá en /asistente discord.":best.answer;
    }
    public String expand(String text,String player){return text.replace("{jugador}",player).replace("{bot}",name);}
    public void validate(){
        if(name==null||normalize(name).isEmpty()||name.length()>32)throw new IllegalArgumentException("nombre vacío o mayor que 32 caracteres");
        if(chatRadius<4||chatRadius>64||visualRadius<16||visualRadius>96||cooldownSeconds<1||cooldownSeconds>60||welcomeDelaySeconds<1||welcomeDelaySeconds>30)throw new IllegalArgumentException("radios o tiempos fuera del rango permitido");
        checkText(firstWelcome);checkText(returnWelcome);
        if(topics==null||topics.isEmpty()||topics.size()>64)throw new IllegalArgumentException("se requieren entre 1 y 64 temas");
        for(var e:topics.entrySet()){
            if(!e.getKey().matches("[a-z0-9_]{1,24}")||e.getValue()==null)throw new IllegalArgumentException("clave de tema inválida");
            checkText(e.getValue().answer);
            if(e.getValue().aliases==null||e.getValue().aliases.isEmpty()||e.getValue().aliases.size()>32)throw new IllegalArgumentException("aliases inválidos");
            for(String a:e.getValue().aliases)if(a==null||normalize(a).isEmpty()||a.length()>80)throw new IllegalArgumentException("alias vacío o demasiado largo");
        }
    }
    private void checkText(String text){if(text==null||text.isBlank()||text.length()>1000)throw new IllegalArgumentException("texto vacío o mayor que 1000 caracteres");}
}
