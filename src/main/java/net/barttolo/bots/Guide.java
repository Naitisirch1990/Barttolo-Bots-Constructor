package net.barttolo.bots;

import java.text.Normalizer;
import java.util.*;

/** Pure conversation matching. No LLM, command execution, or player-chat recording. */
public final class Guide {
    public int schemaVersion=1;
    public String name="Asistente";
    public int chatRadius=32, visualRadius=64, cooldownSeconds=3, welcomeDelaySeconds=3;
    public boolean showInTab=true;
    public String firstWelcome="¡Bienvenido a RingWorld, {jugador}! Soy {bot}, el asistente del spawn.";
    public String returnWelcome="¡Hola de nuevo, {jugador}! Soy {bot}, el asistente del spawn.";
    public Map<String,Topic> topics=new LinkedHashMap<>();
    public static final class Topic {
        public List<String> aliases=new ArrayList<>();
        public String answer="";
        Topic(){}
        Topic(String text,String... words){answer=text;aliases=List.of(words);}
    }
    public static Guide defaults(){
        Guide g=new Guide();g.schemaVersion=3;
        g.topics.put("inicio",new Topic("RingWorld es un mundo de supervivencia con forma de anillo. Explora, construye y participa en actividades del servidor. Pulsa /asistente para abrir los temas.","inicio","tematica","servidor","ringworld","de que trata","como jugar"));
        g.topics.put("misiones",new Topic("La campaña tiene misiones por dimensiones y desafíos con oleadas y jefes. Consulta los requisitos en cada acceso; las recompensas únicas se registran por jugador.","misiones","mision","oleadas","hangar","campana"));
        g.topics.put("eventos",new Topic("Los eventos ofrecen actividades especiales y recompensas. Revisa los anuncios del servidor y Discord para conocer el evento activo.","eventos","evento","semanal","semanales"));
        g.topics.put("asaltos",new Topic("Los asaltos son combates contra jefes en arenas distribuidas por RingWorld. Acércate al punto anunciado y participa con otros jugadores.","asaltos","asalto","jefes","arena","arenas"));
        g.topics.put("invasiones",new Topic("Las invasiones son encuentros de enemigos temáticos que pueden aparecer en el mundo. Mantente alerta y ayuda a defender la zona.","invasiones","invasion","invasores"));
        g.topics.put("reliquias",new Topic("Hay 5 tipos de reliquias. Se obtienen como recompensas únicas de misiones y de algunos jefes, asaltos o eventos especiales. No se compran con R-Coin.","reliquias","reliquia","cuantas reliquias"));
        g.topics.put("ringworld",new Topic("RingWorld es el mundo principal, construido como un anillo. Explora sus aldeas, regiones y puntos de interés; el Nether y el End tienen sus propias aventuras.","ringworld","anillo","mundo principal"));
        g.topics.put("economia",new Topic("R-Coin es la moneda del servidor. Puedes obtenerla mediante actividades y recompensas; consulta tu saldo y las opciones disponibles dentro del juego.","economia","economía","monedas","moneda","r coin","rcoin","saldo"));
        g.topics.put("premios",new Topic("En el shop puedes canjear R-Coin por los premios disponibles. Los precios y el catálogo se consultan dentro del juego. Las reliquias se consiguen jugando y no se venden.","premios","shop","tienda","casino","recompensas","comprar"));
        g.topics.put("armas",new Topic("Prepara tu equipo antes de salir a explorar o entrar en una misión. Consulta las recetas disponibles en JEI.","armas","arma","equipo","crafteo","jei"));
        g.topics.put("reglas",new Topic("Cuidemos una comunidad justa: está prohibido robar, matar a otros jugadores, acosar o discriminar y dañar construcciones ajenas. No se permiten hacks, Xray, clientes modificados para hacer trampas, duplicación de objetos, abuso de errores ni herramientas o métodos que den ventajas injustas o perjudiquen la jugabilidad. Si encontrás un fallo, avisá a la administración y no lo aproveches.","reglas","normas","robar","pvp","hacks","hack","xray","trampas","duplicacion"));
        g.topics.put("discord",new Topic("Comunidad y ayuda de RingWorld: https://discord.gg/xBWHDmKwc", "discord","dc","comunidad","enlace"));
        g.topics.put("saludo",new Topic("¡Hola, {jugador}! Soy {bot}, el asistente del spawn. Pulsa [AYUDA] o escribe /asistente para ver los temas.","hola","buenas","buenos dias","saludos"));
        g.topics.put("identidad",new Topic("Soy {bot}, un bot de ayuda de RingWorld. Tengo respuestas programadas: no soy una persona ni una IA de conversación libre. Mi tarea es darte la bienvenida y orientarte.","quien eres","quien sos","eres un bot","sos un bot","que haces","que estas haciendo"));
        g.topics.put("ayuda",new Topic("Pulsa [AYUDA] en el mensaje de bienvenida o escribe /asistente para abrir los temas. También puedes preguntarme en el chat cerca del spawn.","ayuda","ayudame","comandos","no entiendo"));
        return g;
    }
    public boolean migrate(){
        if(schemaVersion>=3)return false;
        Guide next=defaults();
        if(topics==null)topics=new LinkedHashMap<>();
        topics.remove("tiempos");
        for(String key:next.topics.keySet())topics.putIfAbsent(key,next.topics.get(key));
        Topic oldCoins=topics.get("monedas");
        if(oldCoins!=null&&oldCoins.answer.toLowerCase(Locale.ROOT).contains("b-coin"))topics.put("monedas",next.topics.get("economia"));
        Topic oldEvents=topics.get("eventos");
        if(oldEvents!=null&&oldEvents.answer.toLowerCase(Locale.ROOT).contains("b-coin"))topics.put("eventos",next.topics.get("eventos"));
        name="Asistente";schemaVersion=3;return true;
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
