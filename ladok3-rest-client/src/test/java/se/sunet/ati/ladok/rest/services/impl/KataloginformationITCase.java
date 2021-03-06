package se.sunet.ati.ladok.rest.services.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;

import se.ladok.schemas.kataloginformation.I18N;
import se.ladok.schemas.kataloginformation.I18NLista;
import se.ladok.schemas.kataloginformation.Perioder;
import se.sunet.ati.ladok.rest.services.Kataloginformation;

public class KataloginformationITCase {

	private static Log log = LogFactory.getLog(KataloginformationITCase.class);

	@Test
	public void hamtaOversattningarSvenska() {
		Kataloginformation ki = new KataloginformationImpl();

		I18NLista i18nLista = ki.hamtaOversattningarSvenska();
		assertNotNull(i18nLista);
		assertNotNull(i18nLista.getOversattning());
		assertTrue(!i18nLista.getOversattning().isEmpty());
		I18N iaaa18n = new I18N();
		iaaa18n.setI18NNyckel("commons.fel.detaljkod.antagningsforfarande");
		iaaa18n.setText("Antagningsförfarande");

		for (I18N i18n : i18nLista.getOversattning()) {
			assertTrue(!i18n.getI18NNyckel().equals(iaaa18n.getI18NNyckel())
					|| (i18n.getI18NNyckel().equals(iaaa18n.getI18NNyckel())
							&& i18n.getText().equals(iaaa18n.getText())));
		}

	}

    @Test
    public void listaLokalaPerioder() throws Exception {
        Kataloginformation ki = new KataloginformationImpl();
        Perioder perioder = ki.listaLokalaPerioder();
        assertFalse(perioder.getPeriod().isEmpty());
    }
}
