package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Yama's fire wave (11/28/2026; outstation's docs/hub-submission.md, "A fix: Yama's fire wave"): a graphic's frame
 * sample holds its pose and where it stands, its corners worked out only as the moment's built. They come out as
 * place() made them, and every graphic of a pose shares its see-through-ness rather than a copy each.
 */
public class EncounterGfxTest
{
	@Test
	public void aGraphicsCornersAreWhatPlaceMadeThem()
	{
		float[] x = {0, 64.4f, -64.6f}, y = {-10, -200.5f, 0}, z = {12, -3, 77.5f};
		byte[] alpha = {0, 40};
		EncounterScene.GfxPose p = new EncounterScene.GfxPose(2, 3, 7, x, y, z, alpha, "G3265#2:3:7");
		EncounterScene.Shape sh = new EncounterScene.Shape("g1#2:3:7", EncounterMoment.KIND_GFX, new EncounterMoment.Mesh());
		float px = 5568, py = 5952, h = -360;
		EncounterScene.Sample s = new EncounterScene.Sample(sh, p, px, py, h);
		short[] want = new short[9];
		for (int v = 0; v < 3; v++)
		{
			want[v * 3] = EncounterMoment.Builder.unit(px + x[v]);
			want[v * 3 + 1] = EncounterMoment.Builder.unit(-(h + y[v]));
			want[v * 3 + 2] = EncounterMoment.Builder.unit(-(py + z[v]));
		}
		assertArrayEquals(want, s.xyz());
		assertSame(alpha, s.alpha);
		assertEquals(9, s.xyz().length);
	}

	@Test
	public void anotherModelsCornersAreItsOwn()
	{
		short[] xyz = {1, 2, 3};
		EncounterScene.Sample s = new EncounterScene.Sample(new EncounterScene.Shape("n1", EncounterMoment.KIND_ACTOR,
			new EncounterMoment.Mesh()), xyz, null);
		assertSame(xyz, s.xyz());
	}
}
