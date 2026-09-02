package com.dotgrid.scorewidget

/**
 * Every team the settings menu can offer, per league.
 *
 * Static, and deliberately so. ESPN publishes a `/teams` endpoint that would
 * always be current, and using it would mean a settings screen that cannot list
 * anything without a network - which is the wrong failure for the screen whose
 * whole job is to be opened once, on whatever connection the user happens to
 * have, to pick a team. A relocation costs a line in this file about once every
 * three years.
 *
 * Abbreviations are ESPN's, because they are what arrives on the scoreboard and
 * what a favourite is matched against. A pretty abbreviation that did not match
 * the feed would mean a favourite that never has a game.
 */
object TeamCatalog {

    private fun parse(league: League, rows: List<String>): List<Team> =
        rows.map { row ->
            val abbrev = row.substringBefore('|')
            Team(abbrev, row.substringAfter('|'), league)
        }.sortedBy { it.name }

    private val NFL_ROWS = listOf(
        "ARI|Arizona Cardinals", "ATL|Atlanta Falcons", "BAL|Baltimore Ravens",
        "BUF|Buffalo Bills", "CAR|Carolina Panthers", "CHI|Chicago Bears",
        "CIN|Cincinnati Bengals", "CLE|Cleveland Browns", "DAL|Dallas Cowboys",
        "DEN|Denver Broncos", "DET|Detroit Lions", "GB|Green Bay Packers",
        "HOU|Houston Texans", "IND|Indianapolis Colts", "JAX|Jacksonville Jaguars",
        "KC|Kansas City Chiefs", "LV|Las Vegas Raiders", "LAC|Los Angeles Chargers",
        "LAR|Los Angeles Rams", "MIA|Miami Dolphins", "MIN|Minnesota Vikings",
        "NE|New England Patriots", "NO|New Orleans Saints", "NYG|New York Giants",
        "NYJ|New York Jets", "PHI|Philadelphia Eagles", "PIT|Pittsburgh Steelers",
        "SF|San Francisco 49ers", "SEA|Seattle Seahawks", "TB|Tampa Bay Buccaneers",
        "TEN|Tennessee Titans", "WSH|Washington Commanders"
    )

    private val NBA_ROWS = listOf(
        "ATL|Atlanta Hawks", "BOS|Boston Celtics", "BKN|Brooklyn Nets",
        "CHA|Charlotte Hornets", "CHI|Chicago Bulls", "CLE|Cleveland Cavaliers",
        "DAL|Dallas Mavericks", "DEN|Denver Nuggets", "DET|Detroit Pistons",
        "GS|Golden State Warriors", "HOU|Houston Rockets", "IND|Indiana Pacers",
        "LAC|LA Clippers", "LAL|Los Angeles Lakers", "MEM|Memphis Grizzlies",
        "MIA|Miami Heat", "MIL|Milwaukee Bucks", "MIN|Minnesota Timberwolves",
        "NO|New Orleans Pelicans", "NY|New York Knicks", "OKC|Oklahoma City Thunder",
        "ORL|Orlando Magic", "PHI|Philadelphia 76ers", "PHX|Phoenix Suns",
        "POR|Portland Trail Blazers", "SAC|Sacramento Kings", "SA|San Antonio Spurs",
        "TOR|Toronto Raptors", "UTAH|Utah Jazz", "WSH|Washington Wizards"
    )

    private val MLB_ROWS = listOf(
        "ARI|Arizona Diamondbacks", "ATL|Atlanta Braves", "BAL|Baltimore Orioles",
        "BOS|Boston Red Sox", "CHC|Chicago Cubs", "CWS|Chicago White Sox",
        "CIN|Cincinnati Reds", "CLE|Cleveland Guardians", "COL|Colorado Rockies",
        "DET|Detroit Tigers", "HOU|Houston Astros", "KC|Kansas City Royals",
        "LAA|Los Angeles Angels", "LAD|Los Angeles Dodgers", "MIA|Miami Marlins",
        "MIL|Milwaukee Brewers", "MIN|Minnesota Twins", "NYM|New York Mets",
        "NYY|New York Yankees", "ATH|Athletics", "PHI|Philadelphia Phillies",
        "CHW|Chicago White Sox",
        "PIT|Pittsburgh Pirates", "SD|San Diego Padres", "SF|San Francisco Giants",
        "SEA|Seattle Mariners", "STL|St. Louis Cardinals", "TB|Tampa Bay Rays",
        "TEX|Texas Rangers", "TOR|Toronto Blue Jays", "WSH|Washington Nationals"
    )

    private val NHL_ROWS = listOf(
        "ANA|Anaheim Ducks", "BOS|Boston Bruins", "BUF|Buffalo Sabres",
        "CGY|Calgary Flames", "CAR|Carolina Hurricanes", "CHI|Chicago Blackhawks",
        "COL|Colorado Avalanche", "CBJ|Columbus Blue Jackets", "DAL|Dallas Stars",
        "DET|Detroit Red Wings", "EDM|Edmonton Oilers", "FLA|Florida Panthers",
        "LA|Los Angeles Kings", "MIN|Minnesota Wild", "MTL|Montreal Canadiens",
        "NSH|Nashville Predators", "NJ|New Jersey Devils", "NYI|New York Islanders",
        "NYR|New York Rangers", "OTT|Ottawa Senators", "PHI|Philadelphia Flyers",
        "PIT|Pittsburgh Penguins", "SJ|San Jose Sharks", "SEA|Seattle Kraken",
        "STL|St. Louis Blues", "TB|Tampa Bay Lightning", "TOR|Toronto Maple Leafs",
        "UTAH|Utah Mammoth", "VAN|Vancouver Canucks", "VGK|Vegas Golden Knights",
        "WSH|Washington Capitals", "WPG|Winnipeg Jets"
    )

    /**
     * College football is a hundred and thirty-odd programmes at the top level
     * and this is not all of them.
     *
     * The line has to be drawn somewhere, and it is drawn at the programmes
     * that appear on a national broadcast in a given season - which is the same
     * set most people picking a college team from a phone widget are picking
     * from. Everything else is reachable by the search field on the settings
     * screen, which matches against the feed rather than against this list.
     */
    private val NCAAF_ROWS = listOf(
        "ALA|Alabama Crimson Tide", "AUB|Auburn Tigers", "CLEM|Clemson Tigers",
        "FSU|Florida State Seminoles", "FLA|Florida Gators", "UGA|Georgia Bulldogs",
        "IOWA|Iowa Hawkeyes", "LSU|LSU Tigers", "MIA|Miami Hurricanes",
        "MICH|Michigan Wolverines", "MSU|Michigan State Spartans",
        "MIZ|Missouri Tigers", "ND|Notre Dame Fighting Irish",
        "UNC|North Carolina Tar Heels", "OSU|Ohio State Buckeyes",
        "OU|Oklahoma Sooners", "ORE|Oregon Ducks", "PSU|Penn State Nittany Lions",
        "TA&M|Texas A&M Aggies", "TEX|Texas Longhorns", "TENN|Tennessee Volunteers",
        "UCLA|UCLA Bruins", "USC|USC Trojans", "UTAH|Utah Utes",
        "WASH|Washington Huskies", "WIS|Wisconsin Badgers"
    )

    private val BY_LEAGUE: Map<League, List<Team>> by lazy {
        mapOf(
            League.NFL to parse(League.NFL, NFL_ROWS),
            League.NBA to parse(League.NBA, NBA_ROWS),
            League.MLB to parse(League.MLB, MLB_ROWS),
            League.NHL to parse(League.NHL, NHL_ROWS),
            League.NCAAF to parse(League.NCAAF, NCAAF_ROWS)
        )
    }

    private val BY_KEY: Map<String, Team> by lazy {
        BY_LEAGUE.values.flatten().associateBy { it.key }
    }

    fun teams(league: League): List<Team> = BY_LEAGUE[league].orEmpty()

    /**
     * A team by its stored key.
     *
     * Falls back to a synthesised one rather than null: a favourite saved by a
     * previous version, or a team that has since been renamed, should still
     * show its abbreviation on the settings screen rather than vanishing from a
     * list the user arranged by hand.
     */
    fun byKey(key: String): Team? {
        BY_KEY[key]?.let { return it }
        val league = League.byCode(key.substringBefore("/")) ?: return null
        val abbrev = key.substringAfter("/", "").takeIf { it.isNotBlank() } ?: return null
        return Team(abbrev, abbrev, league)
    }

    /** Case-insensitive match on abbreviation or name, for the settings search field. */
    fun search(league: League, query: String): List<Team> {
        val q = query.trim()
        if (q.isEmpty()) return teams(league)
        return teams(league).filter {
            it.abbrev.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true)
        }
    }
}
