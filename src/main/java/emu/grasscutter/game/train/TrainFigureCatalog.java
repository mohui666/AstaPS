package emu.grasscutter.game.train;

import com.google.gson.JsonParser;
import emu.grasscutter.utils.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Explicit reconstruction settings, distinct from the unavailable native figure-ID mapping. */
public final class TrainFigureCatalog {
    public record Figure(int id, String sgv, String transform, int initialValue) {}
    private final Map<Integer, Figure> figures;

    private TrainFigureCatalog(Map<Integer, Figure> figures) { this.figures = Map.copyOf(figures); }

    private static final class Installed {
        static final TrainFigureCatalog VALUE = load();
    }

    public static TrainFigureCatalog installed() { return Installed.VALUE; }
    public Figure figure(int id) { return figures.get(id); }
    public Collection<Figure> figures() { return figures.values(); }

    private static TrainFigureCatalog load() {
        try {
            var config = JsonParser.parseString(Files.readString(FileUtils.getDataPath(
                    "QuestTrainFigures.json"))).getAsJsonObject();
            if (!config.get("reconstructed").getAsBoolean() || config.get("sceneId").getAsInt() != 3)
                throw new IllegalStateException("Unsupported reconstructed train figure target");
            var figures = new HashMap<Integer, Figure>();
            var sgvNames = new HashSet<String>();
            for (var value : config.getAsJsonArray("figures")) {
                var row = value.getAsJsonObject();
                var figure = new Figure(row.get("questFigureId").getAsInt(), row.get("sgv").getAsString(),
                        row.get("transform").getAsString(), row.get("initialValue").getAsInt());
                // These 14 native consumers attach Enable/Disable modifiers to real named transforms.
                boolean known = figure.sgv.matches("SGV_TrainCollection_0[1-8]")
                        && figure.transform.equals(figure.sgv.substring("SGV_Train".length()))
                        || figure.sgv.matches("SGV_CarriageWallpaper_0[1-6]")
                        && figure.transform.equals(figure.sgv.substring("SGV_".length()));
                if (!known || figure.id <= 0 || figure.initialValue < 0 || figure.initialValue > 1
                        || !sgvNames.add(figure.sgv) || figures.put(figure.id, figure) != null)
                    throw new IllegalStateException("Invalid reconstructed train figure mapping");
            }
            if (figures.size() != 14) throw new IllegalStateException("Missing reconstructed train figures");
            return new TrainFigureCatalog(figures);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read reconstructed train figure mapping", error);
        }
    }
}
