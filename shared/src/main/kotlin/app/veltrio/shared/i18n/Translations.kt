package app.veltrio.shared.i18n

/** Core UI translations for Español (es), Deutsch (de) and Italiano (it).
 *  Any string not listed here falls back to English. */
internal object Translations {
    fun forLang(code: String): Map<String, String> = when (code) {
        "es" -> es
        "de" -> de
        "it" -> it
        else -> emptyMap()
    }

    private val es = mapOf(
        "Accueil" to "Inicio", "Collection" to "Colección", "Direct" to "En directo", "Sources" to "Fuentes",
        "Recherche" to "Buscar", "Réglages" to "Ajustes", "Préférences" to "Preferencias",
        "Apparence" to "Apariencia", "Lecture" to "Reproducción", "Stockage" to "Almacenamiento",
        "À propos" to "Acerca de", "Autres appareils" to "Otros dispositivos", "Comptes" to "Cuentas",
        "Retour" to "Atrás", "RETOUR" to "ATRÁS", "Fermer" to "Cerrar", "FERMER" to "CERRAR",
        "Regarder" to "Ver", "Reprendre" to "Reanudar", "Détails" to "Detalles",
        "Enregistrer" to "Guardar", "Enregistré" to "Guardado", "Bande-annonce" to "Tráiler",
        "Titres similaires" to "Títulos similares", "Sous-titres" to "Subtítulos",
        "Désactivés" to "Desactivados", "Vitesse" to "Velocidad", "Format de l'image" to "Relación de aspecto",
        "Profil de lecture" to "Perfil de reproducción", "Infos média" to "Info multimedia",
        "Taille des sous-titres" to "Tamaño de subtítulos", "Couleur des sous-titres" to "Color de subtítulos",
        "Rechercher OpenSubtitles" to "Buscar en OpenSubtitles", "Passer l'intro" to "Saltar intro",
        "Petit" to "Pequeño", "Normal" to "Normal", "Grand" to "Grande", "Très grand" to "Muy grande",
        "Blanc" to "Blanco", "Jaune" to "Amarillo", "Gris clair" to "Gris claro",
        "Automatique" to "Automático", "Système" to "Sistema", "Sombre" to "Oscuro", "Clair" to "Claro",
        "Films" to "Películas", "Séries" to "Series", "Film" to "Película", "Série" to "Serie",
        "Films populaires" to "Películas populares", "Séries populaires" to "Series populares",
        "Au cinéma" to "En cines", "Séries du moment" to "En emisión",
        "Films les mieux notés" to "Películas mejor valoradas", "Séries les mieux notées" to "Series mejor valoradas",
        "Comédie" to "Comedia", "Drame" to "Drama", "Horreur" to "Terror", "Documentaire" to "Documental",
        "Effacer les filtres" to "Limpiar filtros", "★ Favoris (%s)" to "★ Favoritos (%s)",
        "Guide TV" to "Guía TV", "Liste" to "Lista", "TV en direct" to "TV en directo",
        "Filmographie" to "Filmografía", "Image dans l'image" to "Imagen en imagen",
        "Contrôle parental" to "Control parental", "Mode Enfants" to "Modo Niños",
        "Déverrouiller" to "Desbloquear", "Code PIN" to "Código PIN",
    )

    private val de = mapOf(
        "Accueil" to "Startseite", "Collection" to "Sammlung", "Direct" to "Live-TV", "Sources" to "Quellen",
        "Recherche" to "Suche", "Réglages" to "Einstellungen", "Préférences" to "Einstellungen",
        "Apparence" to "Erscheinungsbild", "Lecture" to "Wiedergabe", "Stockage" to "Speicher",
        "À propos" to "Über", "Autres appareils" to "Andere Geräte", "Comptes" to "Konten",
        "Retour" to "Zurück", "RETOUR" to "ZURÜCK", "Fermer" to "Schließen", "FERMER" to "SCHLIESSEN",
        "Regarder" to "Ansehen", "Reprendre" to "Fortsetzen", "Détails" to "Details",
        "Enregistrer" to "Speichern", "Enregistré" to "Gespeichert", "Bande-annonce" to "Trailer",
        "Titres similaires" to "Ähnliche Titel", "Sous-titres" to "Untertitel",
        "Désactivés" to "Aus", "Vitesse" to "Geschwindigkeit", "Format de l'image" to "Seitenverhältnis",
        "Profil de lecture" to "Wiedergabeprofil", "Infos média" to "Medieninfos",
        "Taille des sous-titres" to "Untertitelgröße", "Couleur des sous-titres" to "Untertitelfarbe",
        "Rechercher OpenSubtitles" to "OpenSubtitles durchsuchen", "Passer l'intro" to "Intro überspringen",
        "Petit" to "Klein", "Normal" to "Normal", "Grand" to "Groß", "Très grand" to "Sehr groß",
        "Blanc" to "Weiß", "Jaune" to "Gelb", "Gris clair" to "Hellgrau",
        "Automatique" to "Automatisch", "Système" to "System", "Sombre" to "Dunkel", "Clair" to "Hell",
        "Films" to "Filme", "Séries" to "Serien", "Film" to "Film", "Série" to "Serie",
        "Films populaires" to "Beliebte Filme", "Séries populaires" to "Beliebte Serien",
        "Au cinéma" to "Im Kino", "Séries du moment" to "Aktuelle Serien",
        "Films les mieux notés" to "Top bewertete Filme", "Séries les mieux notées" to "Top bewertete Serien",
        "Comédie" to "Komödie", "Drame" to "Drama", "Horreur" to "Horror", "Documentaire" to "Dokumentation",
        "Effacer les filtres" to "Filter zurücksetzen", "★ Favoris (%s)" to "★ Favoriten (%s)",
        "Guide TV" to "TV-Programm", "Liste" to "Liste", "TV en direct" to "Live-TV",
        "Filmographie" to "Filmografie", "Image dans l'image" to "Bild-in-Bild",
        "Contrôle parental" to "Kindersicherung", "Mode Enfants" to "Kindermodus",
        "Déverrouiller" to "Entsperren", "Code PIN" to "PIN-Code",
    )

    private val it = mapOf(
        "Accueil" to "Home", "Collection" to "Collezione", "Direct" to "Diretta", "Sources" to "Sorgenti",
        "Recherche" to "Cerca", "Réglages" to "Impostazioni", "Préférences" to "Preferenze",
        "Apparence" to "Aspetto", "Lecture" to "Riproduzione", "Stockage" to "Archiviazione",
        "À propos" to "Info", "Autres appareils" to "Altri dispositivi", "Comptes" to "Account",
        "Retour" to "Indietro", "RETOUR" to "INDIETRO", "Fermer" to "Chiudi", "FERMER" to "CHIUDI",
        "Regarder" to "Guarda", "Reprendre" to "Riprendi", "Détails" to "Dettagli",
        "Enregistrer" to "Salva", "Enregistré" to "Salvato", "Bande-annonce" to "Trailer",
        "Titres similaires" to "Titoli simili", "Sous-titres" to "Sottotitoli",
        "Désactivés" to "Disattivati", "Vitesse" to "Velocità", "Format de l'image" to "Formato immagine",
        "Profil de lecture" to "Profilo di riproduzione", "Infos média" to "Info media",
        "Taille des sous-titres" to "Dimensione sottotitoli", "Couleur des sous-titres" to "Colore sottotitoli",
        "Rechercher OpenSubtitles" to "Cerca su OpenSubtitles", "Passer l'intro" to "Salta sigla",
        "Petit" to "Piccolo", "Normal" to "Normale", "Grand" to "Grande", "Très grand" to "Molto grande",
        "Blanc" to "Bianco", "Jaune" to "Giallo", "Gris clair" to "Grigio chiaro",
        "Automatique" to "Automatico", "Système" to "Sistema", "Sombre" to "Scuro", "Clair" to "Chiaro",
        "Films" to "Film", "Séries" to "Serie", "Film" to "Film", "Série" to "Serie",
        "Films populaires" to "Film popolari", "Séries populaires" to "Serie popolari",
        "Au cinéma" to "Al cinema", "Séries du moment" to "In onda",
        "Films les mieux notés" to "Film più votati", "Séries les mieux notées" to "Serie più votate",
        "Comédie" to "Commedia", "Drame" to "Dramma", "Horreur" to "Horror", "Documentaire" to "Documentario",
        "Effacer les filtres" to "Cancella filtri", "★ Favoris (%s)" to "★ Preferiti (%s)",
        "Guide TV" to "Guida TV", "Liste" to "Elenco", "TV en direct" to "TV in diretta",
        "Filmographie" to "Filmografia", "Image dans l'image" to "Picture-in-Picture",
        "Contrôle parental" to "Controllo parentale", "Mode Enfants" to "Modalità Bambini",
        "Déverrouiller" to "Sblocca", "Code PIN" to "Codice PIN",
    )
}
