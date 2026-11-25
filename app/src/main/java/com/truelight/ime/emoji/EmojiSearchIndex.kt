package com.truelight.ime.emoji

import java.util.Locale

/**
 * Offline, English-only Emoji Search Index + Unicode Skin-Tone Variant resolver.
 *
 * Uses two lazily-allocated parallel String arrays (`cachedEmojis`, `cachedKeywords`) so RAM
 * stays at 0 bytes until emoji search is opened and drops back to 0 bytes immediately on [release].
 */
object EmojiSearchIndex {

    private val SKIN_TONES = arrayOf(
        "\uD83C\uDFFB", // Light
        "\uD83C\uDFFC", // Medium-Light
        "\uD83C\uDFFD", // Medium
        "\uD83C\uDFFE", // Medium-Dark
        "\uD83C\uDFFF"  // Dark
    )

    @Volatile
    private var cachedEmojis: Array<String>? = null

    @Volatile
    private var cachedKeywords: Array<String>? = null

    /**
     * Searches the English emoji keyword index and populates [outResults] ranked by:
     * exact word match > word-prefix match > substring match.
     */
    fun search(query: String, outResults: ArrayList<String>, maxResults: Int = 28) {
        outResults.clear()
        if (maxResults <= 0) return

        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return

        var emojis = cachedEmojis
        var keywords = cachedKeywords
        if (emojis == null || keywords == null) {
            synchronized(this) {
                emojis = cachedEmojis
                keywords = cachedKeywords
                if (emojis == null || keywords == null) {
                    val built = buildIndex()
                    cachedEmojis = built.first
                    cachedKeywords = built.second
                    emojis = built.first
                    keywords = built.second
                }
            }
        }

        val emojiArr = emojis ?: return
        val kwArr = keywords ?: return
        val count = minOf(emojiArr.size, kwArr.size)

        val tier2 = IntArray(maxResults)
        var tier2Count = 0
        val tier1 = IntArray(maxResults)
        var tier1Count = 0

        val hasSpace = q.indexOf(' ') >= 0

        for (i in 0 until count) {
            val score = if (hasSpace) {
                matchMultiWord(kwArr[i], q)
            } else {
                matchToken(kwArr[i], q, 0, q.length)
            }
            if (score == 3) {
                outResults.add(emojiArr[i])
                if (outResults.size >= maxResults) return
            } else if (score == 2 && tier2Count < maxResults) {
                tier2[tier2Count++] = i
            } else if (score == 1 && tier1Count < maxResults) {
                tier1[tier1Count++] = i
            }
        }

        var idx = 0
        while (idx < tier2Count && outResults.size < maxResults) {
            outResults.add(emojiArr[tier2[idx++]])
        }
        idx = 0
        while (idx < tier1Count && outResults.size < maxResults) {
            outResults.add(emojiArr[tier1[idx++]])
        }
    }

    /**
     * Releases the lazy search arrays immediately so standby RAM drops to 0 bytes.
     */
    fun release() {
        synchronized(this) {
            cachedEmojis = null
            cachedKeywords = null
        }
    }

    /**
     * Returns 6 variants `[base, light, mediumLight, medium, mediumDark, dark]` for skin-tone
     * eligible hand/body/people emojis (properly handling `\uFE0F` variation selectors and ZWJ
     * sequences), or `null` for non-skin-tone emojis.
     */
    fun getSkinToneVariants(baseEmoji: String): Array<String>? {
        if (baseEmoji.isEmpty()) return null
        val firstCp = Character.codePointAt(baseEmoji, 0)
        if (!isSkinToneEligible(firstCp)) return null

        val firstLen = Character.charCount(firstCp)
        var pos = firstLen
        var hadVariationSelector = false
        var hadExistingTone = false

        if (pos < baseEmoji.length && baseEmoji[pos] == '\uFE0F') {
            hadVariationSelector = true
            pos++
        }
        if (pos < baseEmoji.length) {
            val nextCp = Character.codePointAt(baseEmoji, pos)
            if (nextCp in 0x1F3FB..0x1F3FF) {
                hadExistingTone = true
                pos += Character.charCount(nextCp)
            }
        }

        val prefix = baseEmoji.substring(0, firstLen)
        val suffix = if (pos < baseEmoji.length) baseEmoji.substring(pos) else ""

        val neutral = when {
            !hadExistingTone -> baseEmoji
            hadVariationSelector || needsDefaultVariationSelector(firstCp) -> "$prefix\uFE0F$suffix"
            else -> "$prefix$suffix"
        }

        return Array(6) { i ->
            if (i == 0) neutral else "$prefix${SKIN_TONES[i - 1]}$suffix"
        }
    }

    private fun needsDefaultVariationSelector(cp: Int): Boolean =
        cp == 0x261D || cp == 0x270C || cp == 0x270D || cp == 0x1F590

    private fun isSkinToneEligible(cp: Int): Boolean = when (cp) {
        // Hands & gestures
        0x261D, // ☝️
        0x270A, // ✊
        0x270B, // ✋
        0x270C, // ✌️
        0x270D, // ✍️
        0x1F446, 0x1F447, 0x1F448, 0x1F449, // 👆👇👈👉
        0x1F44A, 0x1F44B, 0x1F44C, 0x1F44D, 0x1F44E, 0x1F44F, 0x1F450, // 👊👋👌👍👎👏👐
        0x1F485, // 💅
        0x1F4AA, // 💪
        0x1F590, 0x1F595, 0x1F596, // 🖐️🖕🖖
        0x1F64C, 0x1F64F, // 🙌🙏
        0x1F90C, 0x1F90F, // 🤌🤏
        0x1F918, 0x1F919, 0x1F91A, 0x1F91B, 0x1F91C, 0x1F91D, 0x1F91E, 0x1F91F, // 🤘🤙🤚🤛🤜🤝🤞🤟
        0x1F932, 0x1F933, // 🤲🤳
        0x1FAF0, 0x1FAF1, 0x1FAF2, 0x1FAF3, 0x1FAF4, 0x1FAF5, 0x1FAF6, 0x1FAF7, 0x1FAF8, // 🫰🫱🫲🫳🫴🫵🫶🫷🫸
        // Body parts
        0x1F442, 0x1F443, // 👂👃
        0x1F9B5, 0x1F9B6, 0x1F9BB, // 🦵🦶🦻
        // People & activities (includes ZWJ bases like 🧑)
        0x1F3C2, 0x1F3C3, 0x1F3C4, 0x1F3C7, 0x1F3CA, 0x1F3CB, 0x1F3CC, // 🏂🏃🏄🏇🏊🏋🏌
        0x1F466, 0x1F467, 0x1F468, 0x1F469, // 👦👧👨👩
        0x1F46E, 0x1F470, 0x1F471, 0x1F472, 0x1F473, 0x1F474, 0x1F475, 0x1F476, 0x1F477, 0x1F478, // 👮👰👱👲👳👴👵👶👷👸
        0x1F47C, 0x1F481, 0x1F482, 0x1F483, 0x1F486, 0x1F487, // 👼💁💂💃💆💇
        0x1F574, 0x1F575, 0x1F57A, // 🕴🕵🕺
        0x1F645, 0x1F646, 0x1F647, 0x1F64B, 0x1F64D, 0x1F64E, // 🙅🙆🙇🙋🙍🙎
        0x1F6A3, 0x1F6B4, 0x1F6B5, 0x1F6B6, 0x1F6C0, 0x1F6CC, // 🚣🚴🚵🚶🛀🛌
        0x1F926, 0x1F930, 0x1F931, 0x1F934, 0x1F935, 0x1F936, 0x1F937, 0x1F938, 0x1F939, 0x1F93D, 0x1F93E, // 🤦🤰🤱🤴🤵🤶🤷🤸🤹🤽🤾
        0x1F9CF, 0x1F9D1, 0x1F9D2, 0x1F9D3, 0x1F9D4, 0x1F9D5, 0x1F9D6, 0x1F9D7, 0x1F9D8, 0x1F9D9, 0x1F9DA, 0x1F9DB, 0x1F9DC, 0x1F9DD, // 🧏🧑🧒🧓🧔🧕🧖🧗🧘🧙🧚🧛🧜🧝
        0x1F9B8, 0x1F9B9, // 🦸🦹
        0x1FAC3, 0x1FAC4, 0x1FAC5 -> true // 🫃🫄🫅
        else -> false
    }

    private fun matchMultiWord(keywords: String, query: String): Int {
        // First check direct phrase match
        val phraseScore = matchToken(keywords, query, 0, query.length)
        if (phraseScore == 3) return 3

        // Otherwise require every whitespace-separated token in query to match
        var minScore = 3
        var pos = 0
        val len = query.length
        while (pos < len) {
            while (pos < len && query[pos] == ' ') pos++
            if (pos >= len) break
            var end = pos
            while (end < len && query[end] != ' ') end++
            val tokenScore = matchToken(keywords, query, pos, end)
            if (tokenScore == 0) return phraseScore
            if (tokenScore < minScore) minScore = tokenScore
            pos = end
        }
        return maxOf(phraseScore, minScore)
    }

    /**
     * Zero-allocation match scorer for `query[qStart until qEnd]` against space-delimited [keywords].
     * Returns: 3 = exact word match, 2 = word-prefix match, 1 = substring match, 0 = no match.
     */
    private fun matchToken(keywords: String, query: String, qStart: Int, qEnd: Int): Int {
        val qLen = qEnd - qStart
        val kwLen = keywords.length
        if (qLen <= 0 || qLen > kwLen) return 0

        val firstChar = query[qStart]
        val maxStart = kwLen - qLen
        var best = 0
        var i = 0

        while (i <= maxStart) {
            if (keywords[i] == firstChar) {
                var matched = true
                var k = 1
                while (k < qLen) {
                    if (keywords[i + k] != query[qStart + k]) {
                        matched = false
                        break
                    }
                    k++
                }
                if (matched) {
                    val startsWord = (i == 0 || keywords[i - 1] == ' ')
                    val endsWord = (i + qLen == kwLen || keywords[i + qLen] == ' ')
                    if (startsWord && endsWord) return 3
                    if (startsWord && best < 2) {
                        best = 2
                    } else if (best < 1) {
                        best = 1
                    }
                }
            }
            i++
        }
        return best
    }

    private fun buildIndex(): Pair<Array<String>, Array<String>> {
        val capacity = 560
        val emojis = ArrayList<String>(capacity)
        val keywords = ArrayList<String>(capacity)

        fun add(emoji: String, kw: String) {
            emojis.add(emoji)
            keywords.add(kw)
        }

        // --- Smileys & Emotion ---
        add("😀", "grinning smile happy face joy")
        add("😃", "smiley happy grin joy")
        add("😄", "smile happy laugh grin joy")
        add("😁", "grin beaming happy teeth smile")
        add("😆", "laughing haha grin squint funny")
        add("😅", "sweat smile nervous relief awkward")
        add("😂", "joy laugh lol crying tears funny")
        add("🤣", "rofl laugh lol hilarious floor crying")
        add("🥲", "smiling tear touched bittersweet proud")
        add("🥹", "holding back tears emotional touched pleading")
        add("😊", "blush smile happy warm cute")
        add("😇", "angel halo innocent blessed good")
        add("🙂", "slight smile fine ok calm")
        add("🙃", "upside down silly sarcasm playful")
        add("😉", "wink flirt playful joke")
        add("😌", "relieved peaceful calm content")
        add("😍", "heart eyes love crush adore")
        add("🥰", "love hearts smiling adore crush")
        add("😘", "kiss blow heart love flirt")
        add("😗", "kissing face whistle")
        add("😙", "kissing smile eyes")
        add("😚", "kissing closed eyes blush")
        add("😋", "yum delicious tasty tongue food")
        add("😛", "tongue playful silly")
        add("😝", "tongue squint playful silly")
        add("😜", "wink tongue crazy silly")
        add("🤪", "zany crazy goofy silly wild")
        add("🤨", "raised eyebrow skeptical doubt suspect")
        add("🧐", "monocle inspect detective thinking")
        add("🤓", "nerd glasses geek smart study")
        add("😎", "cool sunglasses awesome chill swag")
        add("🥸", "disguise glasses mustache incognito")
        add("🤩", "star struck excited amazed wow")
        add("🥳", "party celebrate birthday hooray fun")
        add("😏", "smirk smug sly flirt")
        add("😒", "unamused annoyed meh bored")
        add("😞", "disappointed sad down upset")
        add("😔", "pensive sad thoughtful sorry")
        add("😟", "worried anxious concerned nervous")
        add("😕", "confused unsure puzzled")
        add("🙁", "frown sad slight")
        add("☹️", "frowning sad unhappy")
        add("😣", "persevere struggle pain frustrated")
        add("😖", "confounded frustrated cringe")
        add("😫", "tired exhausted weary sigh")
        add("😩", "weary tired stressed groan")
        add("🥺", "pleading puppy eyes please cute beg")
        add("😢", "cry sad tear upset")
        add("😭", "sobbing cry loud tears sad")
        add("😮‍💨", "exhale sigh relief tired")
        add("😤", "triumph steam angry huff proud")
        add("😠", "angry mad upset grumpy")
        add("😡", "rage furious angry red mad")
        add("🤬", "cursing swear angry symbols mad")
        add("🤯", "mind blown exploding head shock wow")
        add("😳", "flushed embarrassed shocked blush")
        add("🥵", "hot heat sweat spicy")
        add("🥶", "cold freezing ice chill")
        add("😱", "scream scared fear shock omg")
        add("😨", "fearful scared worried")
        add("😰", "anxious sweat nervous worried")
        add("😥", "sad relieved sweat disappointed")
        add("😓", "downcast sweat stressed")
        add("🫣", "peeking shy scared hide")
        add("🤗", "hug warm embrace welcome")
        add("🫡", "salute respect yes sir")
        add("🤔", "thinking hmm wonder idea")
        add("🫢", "gasp oops surprised mouth")
        add("🤭", "giggle oops shy laugh")
        add("🤫", "shush quiet secret silence")
        add("🫠", "melting embarrassed hot awkward")
        add("🤥", "lying pinocchio nose liar")
        add("😶", "speechless silent blank no mouth")
        add("😶‍🌫️", "clouds fog lost head")
        add("😐", "neutral poker face meh")
        add("😑", "expressionless blank unimpressed")
        add("😬", "grimace awkward cringe yikes")
        add("🫨", "shaking shock vibrate startled")
        add("😯", "hushed surprised oh")
        add("😦", "frowning open mouth surprised")
        add("😧", "anguished worried shock")
        add("😮", "open mouth surprised wow oh")
        add("😲", "astonished shocked amazed")
        add("🥱", "yawn sleepy tired bored")
        add("😴", "sleeping zzz tired bed nap")
        add("🤤", "drool hungry delicious want")
        add("😪", "sleepy tired snot")
        add("😵", "dizzy knockout stunned")
        add("😵‍💫", "spiral eyes dizzy hypnotized confused")
        add("🤐", "zipper mouth secret quiet")
        add("🥴", "woozy drunk dizzy tipsy")
        add("🤢", "nauseated sick green gross")
        add("🤮", "vomit puke sick gross")
        add("🤧", "sneeze sick tissue cold flu")
        add("😷", "mask sick doctor health")
        add("🤒", "thermometer fever sick ill")
        add("🤕", "bandage hurt injured head")
        add("🤑", "money rich dollar cash")
        add("🤠", "cowboy hat western yeehaw")
        add("😈", "devil smiling horns evil mischief")
        add("👿", "angry devil imp evil")
        add("👹", "ogre monster mask demon")
        add("👺", "goblin mask monster")
        add("🤡", "clown joke circus silly")
        add("💩", "poop shit funny crap")
        add("👻", "ghost halloween spooky boo")
        add("💀", "skull dead skeleton rip lol")
        add("☠️", "skull crossbones danger pirate dead")
        add("👽", "alien space ufo et")
        add("👾", "alien monster game retro space")
        add("🤖", "robot bot ai tech machine")
        add("🎃", "pumpkin halloween jackolantern")
        add("😺", "cat smile happy")
        add("😸", "cat grin happy")
        add("😹", "cat joy laugh tears")
        add("😻", "cat heart eyes love")
        add("😼", "cat smirk wry")
        add("😽", "cat kiss")
        add("🙀", "cat weary shock scared")
        add("😿", "cat cry sad tear")
        add("😾", "cat angry pout")
        add("💋", "kiss lips lipstick love")
        add("💌", "love letter mail envelope heart")

        // --- People & Gestures ---
        add("👋", "wave hello hi bye hand")
        add("🤚", "raised back hand stop")
        add("🖐️", "hand fingers splayed five stop")
        add("✋", "raised hand stop high five")
        add("🖖", "vulcan salute spock star trek")
        add("👌", "ok okay perfect nice good hand")
        add("🤌", "pinched fingers italian chef kiss")
        add("🤏", "pinching small tiny little bit")
        add("✌️", "peace victory two v sign")
        add("🤞", "crossed fingers luck hope wish")
        add("🫰", "finger heart snap money love")
        add("🤟", "love you rock hand")
        add("🤘", "rock on metal horns hand")
        add("🤙", "call me shaka hang loose")
        add("👈", "point left finger")
        add("👉", "point right finger")
        add("👆", "point up finger")
        add("🖕", "middle finger rude")
        add("👇", "point down finger")
        add("☝️", "index point up one idea")
        add("🫵", "point you finger")
        add("👍", "thumbs up ok yes like good agree")
        add("👎", "thumbs down no dislike bad disagree")
        add("✊", "fist power punch solidarity")
        add("👊", "punch fist bump bro")
        add("🤛", "left fist bump")
        add("🤜", "right fist bump")
        add("👏", "clap applause bravo congrats")
        add("🙌", "raising hands hooray celebrate praise")
        add("🫶", "heart hands love care")
        add("👐", "open hands hug jazz")
        add("🤲", "palms up pray offer")
        add("🤝", "handshake deal agree partner")
        add("🙏", "pray please thanks grateful namaste hope")
        add("✍️", "writing hand pen note")
        add("💅", "nail polish manicure sass beauty")
        add("🤳", "selfie phone camera")
        add("💪", "muscle strong flex gym bicep power")
        add("🦾", "mechanical arm robot bionic")
        add("🦵", "leg kick")
        add("🦶", "foot kick")
        add("👂", "ear listen hear sound")
        add("👃", "nose smell")
        add("🧠", "brain smart mind think iq")
        add("🫀", "anatomical heart organ")
        add("🫁", "lungs breathe")
        add("🦷", "tooth dentist")
        add("🦴", "bone skeleton dog")
        add("👀", "eyes look see watch peek")
        add("👁️", "eye see look vision")
        add("👅", "tongue taste lick")
        add("👄", "mouth lips kiss")
        add("👶", "baby infant child cute")
        add("🧒", "child kid")
        add("👦", "boy kid son")
        add("👧", "girl kid daughter")
        add("🧑", "person adult")
        add("👨", "man guy male")
        add("🧔", "beard man")
        add("👩", "woman lady female")
        add("🧓", "older person elder")
        add("👴", "old man grandpa grandfather")
        add("👵", "old woman grandma grandmother")
        add("🙅", "no gesture stop cross")
        add("🙆", "ok gesture yes")
        add("💁", "information tip hand sass")
        add("🙋", "raising hand question hi")
        add("🙇", "bow sorry respect thanks")
        add("🤦", "facepalm omg sigh")
        add("🤷", "shrug dunno idk whatever")
        add("🧑‍💻", "developer coder programmer tech laptop")
        add("🧑‍🎓", "student graduate school")
        add("🧑‍🎨", "artist painter art")
        add("🧑‍🚀", "astronaut space rocket")
        add("🧑‍🍳", "chef cook food kitchen")
        add("🧑‍⚕️", "doctor nurse health medical")
        add("🧑‍🏫", "teacher professor school")
        add("💃", "dance woman salsa party")
        add("🕺", "dance man disco party")
        add("🏃", "run runner exercise fast jog")
        add("🚶", "walk pedestrian stroll")
        add("🧘", "yoga meditate zen calm")
        add("🫂", "hug embrace comfort people")
        add("🗣️", "speaking voice talk shout")
        add("👤", "user profile person silhouette")
        add("👥", "users group team people")
        add("👣", "footprints steps walk")

        // --- Animals & Nature ---
        add("🐶", "dog puppy pet woof")
        add("🐱", "cat kitty pet meow")
        add("🐭", "mouse rat rodent")
        add("🐹", "hamster pet cute")
        add("🐰", "rabbit bunny easter pet")
        add("🦊", "fox clever animal")
        add("🐻", "bear animal")
        add("🐼", "panda bear cute")
        add("🐻‍❄️", "polar bear snow arctic")
        add("🐨", "koala australia cute")
        add("🐯", "tiger cat wild")
        add("🦁", "lion king wild roar")
        add("🐮", "cow moo farm milk")
        add("🐷", "pig oink farm")
        add("🐸", "frog toad green ribbit")
        add("🐵", "monkey ape banana")
        add("🙈", "see no evil monkey shy oops")
        add("🙉", "hear no evil monkey")
        add("🙊", "speak no evil monkey secret")
        add("🐒", "monkey animal")
        add("🐔", "chicken hen bird farm")
        add("🐧", "penguin bird cold ice")
        add("🐦", "bird tweet fly")
        add("🐤", "chick baby bird yellow")
        add("🐣", "hatching chick egg bird")
        add("🐥", "chick bird cute")
        add("🦆", "duck quack bird")
        add("🦅", "eagle bird fly freedom")
        add("🦉", "owl bird night wise")
        add("🦇", "bat night vampire halloween")
        add("🐺", "wolf dog howl wild")
        add("🐗", "boar pig wild")
        add("🐴", "horse pony ride")
        add("🦄", "unicorn magic fantasy horse")
        add("🐝", "bee honey bug buzz")
        add("🐛", "bug caterpillar insect")
        add("🦋", "butterfly insect pretty fly")
        add("🐌", "snail slow shell")
        add("🐞", "ladybug bug insect")
        add("🐜", "ant bug insect")
        add("🐢", "turtle slow shell sea")
        add("🐍", "snake reptile hiss")
        add("🦎", "lizard gecko reptile")
        add("🐙", "octopus sea ocean")
        add("🦑", "squid sea ocean")
        add("🦀", "crab sea beach")
        add("🐬", "dolphin sea ocean smart")
        add("🐳", "whale sea ocean spout")
        add("🪴", "potted plant houseplant green")
        add("🌲", "evergreen pine tree forest")
        add("🌳", "tree forest nature green")
        add("🌴", "palm tree beach tropical summer")
        add("🌵", "cactus desert plant")
        add("🌾", "rice wheat grain harvest")
        add("🌿", "herb leaf plant green")
        add("☘️", "shamrock clover irish green")
        add("🍀", "four leaf clover lucky green")
        add("🍁", "maple leaf autumn fall canada")
        add("🍂", "fallen leaf autumn fall")
        add("🍃", "leaf wind breeze green")
        add("🌸", "cherry blossom sakura flower pink spring")
        add("🌺", "hibiscus flower tropical")
        add("🌻", "sunflower flower yellow summer")
        add("🌹", "rose flower red love romance")
        add("🌷", "tulip flower spring pink")
        add("🌼", "blossom flower daisy yellow")
        add("🌙", "moon crescent night sleep")
        add("☀️", "sun sunny bright weather summer")
        add("⭐", "star favorite rating night")
        add("🌈", "rainbow pride colors sky")
        add("☁️", "cloud weather sky")
        add("⛈️", "thunderstorm rain lightning storm")
        add("❄️", "snowflake cold winter ice snow")
        add("💧", "droplet water sweat tear")
        add("🌊", "wave ocean sea water surf")
        add("🔥", "fire hot lit flame burn trending")
        add("🌍", "earth globe world planet")
        add("🍄", "mushroom fungus nature")

        // --- Food & Drink ---
        add("🍏", "green apple fruit")
        add("🍎", "red apple fruit healthy")
        add("🍐", "pear fruit")
        add("🍊", "orange tangerine citrus fruit")
        add("🍋", "lemon sour citrus fruit")
        add("🍌", "banana fruit yellow")
        add("🍉", "watermelon fruit summer")
        add("🍇", "grapes fruit wine")
        add("🍓", "strawberry berry fruit red")
        add("🫐", "blueberry berry fruit")
        add("🍈", "melon fruit")
        add("🍒", "cherries fruit red")
        add("🍑", "peach fruit")
        add("🥭", "mango tropical fruit")
        add("🍍", "pineapple tropical fruit")
        add("🥥", "coconut tropical")
        add("🥝", "kiwi fruit green")
        add("🍅", "tomato vegetable red")
        add("🥑", "avocado green healthy")
        add("🥦", "broccoli vegetable green")
        add("🥬", "leafy green lettuce vegetable")
        add("🥒", "cucumber pickle vegetable")
        add("🌶️", "hot pepper chili spicy")
        add("🌽", "corn maize vegetable")
        add("🥕", "carrot vegetable orange")
        add("🧄", "garlic spice cooking")
        add("🧅", "onion vegetable cooking")
        add("🥔", "potato vegetable")
        add("🍠", "sweet potato yam")
        add("🍞", "bread toast loaf")
        add("🥐", "croissant french pastry bakery")
        add("🥖", "baguette french bread")
        add("🥨", "pretzel snack")
        add("🥯", "bagel bread breakfast")
        add("🥞", "pancakes breakfast syrup")
        add("🧇", "waffle breakfast")
        add("🧀", "cheese wedge dairy")
        add("🍖", "meat bone bbq")
        add("🍗", "chicken leg drumstick poultry")
        add("🥩", "steak meat beef")
        add("🥓", "bacon breakfast pork")
        add("🍔", "burger hamburger cheeseburger fast food")
        add("🍟", "fries french fries fast food")
        add("🍕", "pizza slice italian food")
        add("🌭", "hotdog sausage fast food")
        add("🥪", "sandwich lunch bread")
        add("🌮", "taco mexican food")
        add("🌯", "burrito wrap mexican food")
        add("🍜", "noodles ramen pho soup bowl")
        add("🍲", "pot stew soup hotpot food")
        add("🥣", "bowl cereal soup")
        add("🥗", "salad green healthy food")
        add("🍿", "popcorn movie snack cinema")
        add("🧈", "butter dairy")
        add("🧂", "salt seasoning")
        add("🍱", "bento lunch box japanese")
        add("🍙", "rice ball onigiri japanese")
        add("🍚", "rice bowl asian food")
        add("🍛", "curry rice food")
        add("🍣", "sushi japanese fish food")
        add("🍤", "shrimp tempura fried seafood")
        add("🥟", "dumpling gyoza dimsum")
        add("🍦", "ice cream cone dessert sweet")
        add("🍩", "donut doughnut dessert sweet")
        add("🍪", "cookie biscuit dessert sweet")
        add("🎂", "birthday cake celebrate party dessert")
        add("🍰", "cake shortcake dessert sweet")
        add("🧁", "cupcake muffin dessert sweet")
        add("🍫", "chocolate candy sweet")
        add("☕", "coffee espresso latte morning drink cafe")
        add("🫖", "teapot tea drink")
        add("🍵", "green tea matcha drink")
        add("🧋", "boba bubble tea milk tea drink")
        add("🍺", "beer mug drink pub")
        add("🍻", "beers cheers clink drink party")

        // --- Travel & Places ---
        add("🚗", "car auto drive vehicle")
        add("🚕", "taxi cab car ride")
        add("🚙", "suv car vehicle")
        add("🚌", "bus transit public")
        add("🏎️", "race car fast f1")
        add("🚓", "police car cop siren")
        add("🚑", "ambulance emergency hospital")
        add("🚒", "fire truck engine emergency")
        add("🚚", "delivery truck shipping")
        add("🚜", "tractor farm")
        add("🛴", "scooter kick")
        add("🚲", "bicycle bike cycle ride")
        add("🛵", "scooter vespa motorbike")
        add("🏍️", "motorcycle bike race")
        add("🚨", "siren alarm police alert emergency")
        add("🚆", "train rail transit")
        add("🚇", "metro subway train")
        add("✈️", "airplane plane flight travel fly")
        add("🛫", "departure flight takeoff plane")
        add("🛬", "arrival flight landing plane")
        add("🚀", "rocket space launch startup fast")
        add("🛸", "ufo alien saucer space")
        add("🚁", "helicopter chopper fly")
        add("⛵", "sailboat boat sea wind")
        add("🚤", "speedboat boat water")
        add("🚢", "ship cruise boat sea")
        add("⚓", "anchor ship sea navy")
        add("🏠", "house home building")
        add("🏡", "house garden home")
        add("🏢", "office building work")
        add("🏥", "hospital medical doctor")
        add("🏦", "bank money finance")
        add("🏨", "hotel stay travel")
        add("🏪", "store shop convenience")
        add("🏫", "school education study")
        add("🏭", "factory industry")
        add("🏰", "castle palace fantasy")
        add("🗼", "tokyo tower landmark")
        add("🗽", "statue liberty new york usa")
        add("⛲", "fountain park water")
        add("⛺", "tent camping outdoor")
        add("🌃", "night stars city")
        add("🏙️", "cityscape skyline urban")
        add("🌅", "sunrise morning beach")
        add("🌇", "sunset evening city")
        add("🌉", "bridge night city")
        add("🗺️", "world map travel geography")
        add("🧭", "compass direction navigate")
        add("🏔️", "mountain snow peak nature")

        // --- Activities ---
        add("⚽", "soccer football ball sport")
        add("🏀", "basketball ball sport nba")
        add("🏈", "football american sport")
        add("⚾", "baseball sport")
        add("🎾", "tennis racket sport")
        add("🏐", "volleyball sport")
        add("🏉", "rugby sport")
        add("🎱", "pool billiards eight ball")
        add("🏓", "ping pong table tennis")
        add("🏸", "badminton sport")
        add("🏒", "hockey ice sport")
        add("🏏", "cricket sport")
        add("⛳", "golf flag sport")
        add("🏹", "archery bow arrow target")
        add("🎣", "fishing pole fish")
        add("🥊", "boxing glove fight sport")
        add("🥋", "martial arts karate judo")
        add("🛹", "skateboard skate")
        add("🎯", "target bullseye dart goal")
        add("🎮", "video game controller gaming play")
        add("🕹️", "joystick arcade game")
        add("🎰", "slot machine casino jackpot")
        add("🎲", "dice game luck board")
        add("🧩", "puzzle piece jigsaw")
        add("🧸", "teddy bear toy cute")
        add("🎨", "art palette paint draw creative")
        add("🎭", "theater drama masks acting")
        add("🎪", "circus tent show")
        add("🎤", "microphone sing karaoke podcast")
        add("🎧", "headphones music audio listen")
        add("🎼", "music score notes")
        add("🎹", "piano keyboard music")
        add("🥁", "drum music beat")
        add("🎷", "saxophone jazz music")
        add("🎺", "trumpet music")
        add("🎸", "guitar rock music")
        add("🎻", "violin music classical")
        add("🏆", "trophy winner cup award champion")
        add("🥇", "gold medal first winner")
        add("🥈", "silver medal second")
        add("🥉", "bronze medal third")
        add("🏅", "medal sports award")
        add("🎉", "party popper confetti celebrate congrats")
        add("🎊", "confetti ball celebrate party")
        add("🎈", "balloon party birthday")
        add("🎁", "gift present birthday wrap")
        add("🎀", "ribbon bow pink")
        add("🎫", "ticket event pass")
        add("🎬", "clapper movie film cinema action")
        add("📸", "camera flash photo picture")
        add("🎳", "bowling strike game")

        // --- Objects & Tech ---
        add("📱", "phone smartphone mobile iphone android")
        add("☎️", "telephone phone call")
        add("📞", "phone receiver call")
        add("🔋", "battery power charge energy")
        add("🪫", "low battery dead power")
        add("🔌", "plug electric power cable")
        add("💻", "laptop computer pc mac code work")
        add("🖥️", "desktop computer monitor screen")
        add("🖨️", "printer paper office")
        add("⌨️", "keyboard typing computer")
        add("🖱️", "computer mouse click")
        add("💾", "floppy disk save retro")
        add("💿", "cd disc music")
        add("📷", "camera photo picture")
        add("📹", "video camera record")
        add("🎥", "movie camera film")
        add("📡", "satellite antenna signal")
        add("💡", "light bulb idea bright tip")
        add("🔦", "flashlight torch light")
        add("🕯️", "candle light warm")
        add("📕", "book red read")
        add("📖", "open book read study")
        add("📗", "green book read")
        add("📘", "blue book read")
        add("📚", "books library study read")
        add("📓", "notebook journal notes")
        add("📄", "page document file paper")
        add("📰", "newspaper news press")
        add("🔖", "bookmark save tag")
        add("🏷️", "label price tag")
        add("💰", "money bag cash rich dollar")
        add("🪙", "coin money gold")
        add("💵", "dollar bill money cash")
        add("💶", "euro money")
        add("💷", "pound money")
        add("💸", "money wings spend flying cash")
        add("💳", "credit card pay bank")
        add("🧾", "receipt bill invoice")
        add("✉️", "envelope mail email letter")
        add("📧", "email mail message")
        add("📦", "package box parcel delivery")
        add("✏️", "pencil write draw edit")
        add("🖊️", "pen write sign")
        add("🖌️", "paintbrush paint art")
        add("📝", "memo note write list")
        add("📁", "folder file directory")
        add("📂", "open folder file")
        add("📅", "calendar date schedule event")
        add("📌", "pushpin pin location")
        add("📍", "pin location map")
        add("📎", "paperclip attach")
        add("✂️", "scissors cut craft")
        add("🔒", "lock secure private password")
        add("🔓", "unlock open security")
        add("🔑", "key password secret unlock")
        add("🔨", "hammer tool build fix")
        add("🔧", "wrench tool fix repair")
        add("⚙️", "gear settings config cog")
        add("🔬", "microscope science lab research")

        // --- Hearts & Symbols ---
        add("❤️", "red heart love romance")
        add("🧡", "orange heart love")
        add("💛", "yellow heart love friendship")
        add("💚", "green heart love")
        add("💙", "blue heart love")
        add("💜", "purple heart love")
        add("🖤", "black heart dark love")
        add("🤍", "white heart pure love")
        add("🤎", "brown heart love")
        add("💔", "broken heart sad breakup")
        add("❤️‍🔥", "heart fire passion love")
        add("❤️‍🩹", "mending heart heal recovery")
        add("❣️", "heart exclamation love")
        add("💕", "two hearts love cute")
        add("💞", "revolving hearts love")
        add("💓", "beating heart love")
        add("💗", "growing heart love")
        add("💖", "sparkling heart love")
        add("💘", "cupid arrow heart love")
        add("💝", "ribbon heart gift love")
        add("💟", "heart decoration")
        add("☮️", "peace symbol")
        add("✝️", "cross christian")
        add("☪️", "star crescent islam")
        add("☯️", "yin yang balance")
        add("⚛️", "atom science physics")
        add("☢️", "radioactive nuclear danger")
        add("☣️", "biohazard danger")
        add("🌟", "glowing star bright shine")
        add("✨", "sparkles magic shiny clean new")
        add("⚡", "lightning bolt zap fast energy electric")
        add("💥", "boom explosion collision bang")
        add("💯", "hundred 100 perfect score agree")
        add("💢", "anger symbol mad comic")
        add("♨️", "hot springs steam")
        add("🛑", "stop sign halt")
        add("✅", "check mark done yes ok green")
        add("❌", "cross mark no wrong x cancel")
        add("❓", "question mark help ask")
        add("❗", "exclamation alert important")
        add("‼️", "double exclamation alert")
        add("⁉️", "exclamation question what")
        add("♻️", "recycle green eco")
        add("✔️", "check tick yes ok")
        add("☑️", "ballot check box")
        add("🔔", "bell notification alert ring")
        add("🔕", "muted bell silent no notification")

        // --- Flags ---
        add("🇻🇳", "vietnam flag vn")
        add("🇺🇸", "usa united states america flag us")
        add("🇬🇧", "uk united kingdom britain england flag gb")
        add("🇫🇷", "france french flag fr")
        add("🇩🇪", "germany german flag de")
        add("🇯🇵", "japan japanese flag jp")
        add("🇰🇷", "south korea korean flag kr")
        add("🇨🇳", "china chinese flag cn")
        add("🇨🇦", "canada canadian flag ca")
        add("🇦🇺", "australia australian flag au")
        add("🇷🇺", "russia russian flag ru")
        add("🇮🇳", "india indian flag in")
        add("🇧🇷", "brazil brazilian flag br")
        add("🇮🇹", "italy italian flag it")
        add("🇪🇸", "spain spanish flag es")
        add("🇸🇬", "singapore flag sg")
        add("🇹🇭", "thailand thai flag th")
        add("🇲🇾", "malaysia flag my")
        add("🇮🇩", "indonesia flag id")
        add("🇵🇭", "philippines flag ph")
        add("🇱🇦", "laos flag la")
        add("🇰🇭", "cambodia flag kh")
        add("🇲🇲", "myanmar flag mm")
        add("🇹🇼", "taiwan flag tw")
        add("🇭🇰", "hong kong flag hk")
        add("🇳🇱", "netherlands dutch flag nl")
        add("🇵🇱", "poland polish flag pl")
        add("🇸🇪", "sweden swedish flag se")
        add("🇨🇭", "switzerland swiss flag ch")
        add("🇹🇷", "turkey turkish flag tr")
        add("🇺🇦", "ukraine ukrainian flag ua")
        add("🇲🇽", "mexico mexican flag mx")
        add("🇦🇷", "argentina flag ar")
        add("🇳🇿", "new zealand flag nz")
        add("🇵🇹", "portugal portuguese flag pt")
        add("🏁", "checkered flag finish race")
        add("🚩", "red flag warning")
        add("🏳️", "white flag surrender")
        add("🏳️‍🌈", "rainbow pride flag lgbt")
        add("🏳️‍⚧️", "transgender pride flag")

        return emojis.toTypedArray() to keywords.toTypedArray()
    }
}
