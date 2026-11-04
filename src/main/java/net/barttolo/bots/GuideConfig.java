package net.barttolo.bots;

import com.google.gson.*;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;

public final class GuideConfig {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static Path file(){return FMLPaths.CONFIGDIR.get().resolve("barttolo-bots-guia.json");}
    public static Guide load()throws IOException {
        Path p=file();
        if(!Files.exists(p)){
            Guide initial=Guide.defaults();Files.createDirectories(p.getParent());Files.writeString(p,GSON.toJson(initial),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);return initial;
        }
        if(Files.size(p)>128*1024)throw new IOException("La guía supera 128 KiB");
        try(var reader=Files.newBufferedReader(p,StandardCharsets.UTF_8)){
            Guide result=GSON.fromJson(reader,Guide.class);
            if(result==null)throw new IllegalArgumentException("guía vacía");
            boolean upgraded=result.migrate();result.validate();
            if(upgraded){
                Path backup=p.resolveSibling(p.getFileName()+".pre-test2.bak");
                if(!Files.exists(backup))Files.copy(p,backup);
                Path temporary=Files.createTempFile(p.getParent(),"barttolo-bots-guia-",".tmp");
                try{
                    Files.writeString(temporary,GSON.toJson(result),StandardCharsets.UTF_8);
                    Files.move(temporary,p,StandardCopyOption.REPLACE_EXISTING);
                }finally{Files.deleteIfExists(temporary);}
            }
            return result;
        }catch(JsonParseException|IllegalArgumentException e){throw new IOException("Guía inválida: "+e.getMessage(),e);}
    }
}
