package wiki.outside;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** RuneLite with this plugin loaded, in developer mode (`py plugin/run.py client`, or this main from an IDE). */
public class OutsidePluginTest
{
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OutsidePlugin.class);
		RuneLite.main(args);
	}
}
