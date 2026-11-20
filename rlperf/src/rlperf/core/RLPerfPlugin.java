package rlperf.core;

import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/** Coremod entry point. Sorting index above 1000 so the transformer sees SRG names. */
@IFMLLoadingPlugin.Name("RLPerf")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"rlperf."})
public class RLPerfPlugin implements IFMLLoadingPlugin {
    @Override public String[] getASMTransformerClass() { return new String[] {"rlperf.core.RLPerfTransformer"}; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) {}
    @Override public String getAccessTransformerClass() { return null; }
}
