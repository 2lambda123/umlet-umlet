package com.baselet.diagram.draw;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import com.baselet.control.StringStyle;
import com.baselet.control.enums.AlignHorizontal;
import com.baselet.control.enums.AlignVertical;
import com.baselet.control.enums.FormatLabels;
import com.baselet.diagram.draw.helper.Style;
import com.baselet.util.LRUCache;

/**
 * Based on the old TextSplitter, but offers additional features.
 * e.g. splitting a whole line into the parts
 * calculate the height
 * calculate minimum width of an text so it can be fully drawn
 *
 * If not stated otherwise all Strings supplied to the TextSplitter are interpreted and
 * the found markup is used to format the String (see {@link StringStyle}).
 * <br>
 * <i>Hint: The old TextSplitter can be found in the git history</i>
 * <pre>e.g. hash: 1320ae5858446795bccda8d2bb12d18664278886</pre>
 */
@SuppressWarnings("unused")
public class TextSplitter {

	// since the 2nd and 3rd cache use the value of the 1st as a partial key, the size shouldn't be too different
	// especially for the 2nd, the 3rd is bigger because there will be many different width value because of resize operations
	private static final int WORD_CACHE_SIZE = 180;
	private static final int MIN_WIDTH_CACHE_SIZE = 190;
	private static final int WORDWRAP_CACHE_SIZE = 400;

	private static final String SPLIT_CHARS = " \t";

	// 3 Caches are used
	// String line -> WordRegion[] words
	// WordRegion[] words + Style style + FormatLabels -> Double minWidth
	// WordRegion[] words + Style style + FormatLabels + Double width -> String[] wrappedLines + double height

	private static LinkedHashMap<String, WordRegion[]> wordCache = new LRUCache<String, WordRegion[]>(WORD_CACHE_SIZE);
	private static LinkedHashMap<MinWidthCacheKey, Double> minWidthCache = new LRUCache<MinWidthCacheKey, Double>(MIN_WIDTH_CACHE_SIZE);
	private static LinkedHashMap<WordwrapCacheKey, WordwrapCacheValue> wordwrapCache = new LRUCache<WordwrapCacheKey, WordwrapCacheValue>(WORDWRAP_CACHE_SIZE);

	private static final Logger log = Logger.getLogger(TextSplitter.class);

	/**
	 *
	 * @param drawer
	 * @param textLines each element is a line, must fit into width,height Rectangle
	 * @param topLeftX
	 * @param topLeftY
	 * @param width
	 * @param height
	 * @param hAlignment
	 * @param vAlignment
	 */
	public static void drawText(DrawHandler drawer, String[] textLines, double topLeftX, double topLeftY, double width, double height, AlignHorizontal hAlignment, AlignVertical vAlignment) {
		double textHeight = getSplitStringHeight(textLines, width, drawer);
		if (textHeight > height) {
			throw new IllegalArgumentException("The text needs more height then specified in the parameter");
		}
		switch (vAlignment) {
			case TOP:
				break;
			case CENTER:
				topLeftY += (height - textHeight) / 2.0;
				break;
			case BOTTOM:
				topLeftY += height - textHeight;
				break;
			default:
				log.error("Encountered unhandled enumeration value '" + vAlignment + "'.");
				break;
		}
		topLeftY += drawer.textHeightMax();
		switch (hAlignment) {
			case LEFT:
				break;
			case CENTER:
				topLeftX += width / 2.0;
				break;
			case RIGHT:
				topLeftX += width;
				break;
			default:
				log.error("Encountered unhandled enumeration value '" + hAlignment + "'.");
				break;
		}
		for (String l : textLines) {
			for (StringStyle wl : splitStringAlgorithm(l, width, drawer)) {
				drawer.print(wl, topLeftX, topLeftY, hAlignment);
				topLeftY += drawer.textHeightMaxWithSpace();
			}
		}

	}

	/**
	 * checks if the whole string would fit into the width
	 * @param text
	 * @param width
	 * @param drawer
	 * @return true if the whole string would fit into the width
	 */
	public static boolean checkifStringFitsNoWordwrap(String text, double width, DrawHandler drawer) {
		StringStyle analyzedText = StringStyle.analyzeFormatLabels(StringStyle.replaceNotEscaped(text));
		WordRegion[] words = getCachedWords(analyzedText.getStringWithoutMarkup()); // only check cache because we don't need the words
		if (words == null) {
			return drawer.textWidth(analyzedText.getStringWithoutMarkup()) + endBuffer(drawer) + 0.01 < width;
		}
		else
		{
			WordwrapCacheValue wwValue = getCachedWordwrap(words, width, drawer.getStyleClone(), analyzedText.getFormat());
			if (wwValue == null) {
				return drawer.textWidth(analyzedText.getStringWithoutMarkup()) + endBuffer(drawer) + 0.01 < width;
			}
			else {
				return wwValue.getWrappedLines().length < 2; // if only 1 line was generated then it fits
			}
		}
	}

	/**
	 * checks if the minimum width exceeds the given width
	 * @param text
	 * @param width
	 * @param drawer
	 * @return true if the minimum width does not exceed the given width
	 */
	public static boolean checkifStringFitsWithWordwrap(String text, double width, DrawHandler drawer) {
		return getTextMinWidth(text, drawer) < width; // generate the words and min width (or take them from cache)
	}

	/**
	 * Splits the text so it can be drawn with the given width and then the height is calculated.
	 * @param text a single line (no \r \n)
	 * @param width
	 * @param drawer
	 * @return the split text, each line as an element
	 *
	 * @see #splitStringAndHeightAlgorithm(String, double, DrawHandler, boolean)
	 */
	public static double getSplitStringHeight(String text, double width, DrawHandler drawer) {
		return splitStringAndHeightAlgorithm(text, width, drawer, false).getHeight();
	}

	/**
	 * Splits each line so it can be drawn with the given width and then the height is calculated.
	 * It only call getSplitStringHeight(String, double, DrawHanlder) for each string and adds up the height
	 * @param textLines each element is a single line (no \r \n)
	 * @param width
	 * @param drawer
	 * @return the split text, each line as an element
	 *
	 * @see #splitStringAndHeightAlgorithm(String, double, DrawHandler, boolean)
	 */
	public static double getSplitStringHeight(String[] textLines, double width, DrawHandler drawer) {
		double height = 0;
		for (String l : textLines) {
			height += getSplitStringHeight(l, width, drawer);
		}
		return height;
	}

	/**
	 * Splits the text so it can be drawn with the given width, if a single word would exceed the width it is truncated.
	 * @param text a single line (no \r \n)
	 * @param width
	 * @param drawer
	 * @return the split text, each line as an element
	 *
	 * @see #splitStringAlgorithm(String, double, DrawHandler, boolean)
	 */
	public static StringStyle[] splitStringAlgorithm(String text, double width, DrawHandler drawer) {
		return splitStringAlgorithm(text, width, drawer, false);
	}

	/**
	 *
	 * @param text  a single line (no \r \n)
	 * @param width in which the text should be fitted, need to be &gt; the width of the 'n' character
	 * @param drawer
	 * @param runtimeException if true then a runtime exception is thrown if a single word is to big for the given width
	 * @return the wrapped lines
	 */
	public static StringStyle[] splitStringAlgorithm(String text, double width, DrawHandler drawer, boolean runtimeException) {
		return splitStringAndHeightAlgorithm(text, width, drawer, runtimeException).getWrappedLines();
	}

	/**
	 *
	 * @param text1  a single line (no \r \n)
	 * @param width in which the text should be fitted, need to be &gt; the width of the 'n' character
	 * @param drawer
	 * @param runtimeException if true then a runtime exception is thrown if a single word is to big for the given width
	 * @return
	 */
	private static WordwrapCacheValue splitStringAndHeightAlgorithm(String text, double width, DrawHandler drawer, boolean runtimeException) {
		StringStyle analyzedText = StringStyle.analyzeFormatLabels(StringStyle.replaceNotEscaped(text));
		String finalText = analyzedText.getStringWithoutMarkup();
		WordRegion[] words = splitIntoWords(finalText);
		WordwrapCacheKey key = new WordwrapCacheKey(words, width, drawer.getStyleClone(), analyzedText.getFormat());
		if (getCachedWordwrap(key) != null) {
			return getCachedWordwrap(key);
		}
		else {
			List<StringStyle> wrappedText = new LinkedList<StringStyle>();
			if (words.length > 0) {
				width -= endBuffer(drawer); // subtract a buffer to make sure no character is hidden at the end (borrowed from TextSplitter)
				if (width <= 0) {
					throw new IllegalArgumentException("The width needs to be bigger then the size of a 'n' character.");
				}

				int lineStart = 0;
				for (int i = 0; i < words.length; i++) {
					if (drawer.textWidth(finalText.substring(words[lineStart].getBegin(), words[i].getEnd())) > width) {
						if (lineStart == i) {
							// the single word doesn't fit into the width!
							if (runtimeException) {
								throw new RuntimeException("At least one word is to big for the specified width!");
							}
							else
							{
								int endIndex = words[lineStart].getEnd() - 1;
								while (drawer.textWidth(finalText.substring(words[lineStart].getBegin(), endIndex)) > width) {
									endIndex--;
								}
								wrappedText.add(new StringStyle(analyzedText.getFormat(),
										finalText.substring(words[lineStart].getBegin(), endIndex)));
								lineStart = i;
							}
						}
						else {
							wrappedText.add(new StringStyle(analyzedText.getFormat(),
									finalText.substring(words[lineStart].getBegin(), words[i - 1].getEnd())));
							lineStart = i;
						}
					}
				}
				wrappedText.add(new StringStyle(analyzedText.getFormat(),
						finalText.substring(words[lineStart].getBegin(), words[words.length - 1].getEnd())));
			}
			else {
				wrappedText.add(new StringStyle(Collections.<FormatLabels> emptySet(), ""));
			}
			double height = wrappedText.size() * drawer.textHeightMaxWithSpace();
			WordwrapCacheValue wordwrapValue = new WordwrapCacheValue(wrappedText.toArray(new StringStyle[0]), height);
			setCachedWordwrap(key, wordwrapValue);
			return wordwrapValue;
		}
	}

	/**
	 *
	 * @param text  a single line (no \r \n)
	 * @param drawer
	 * @return the minimum width, which is needed to draw the text. This is based on the biggest word.
	 */
	public static double getTextMinWidth(String text, DrawHandler drawer) {
		StringStyle analyzedText = StringStyle.analyzeFormatLabels(StringStyle.replaceNotEscaped(text));
		MinWidthCacheKey key = new MinWidthCacheKey(splitIntoWords(analyzedText.getStringWithoutMarkup()),
				drawer.getStyleClone(), analyzedText.getFormat());
		if (getCachedMinWidth(key) != null) {
			return getCachedMinWidth(key);
		}
		else {
			double minWidth = 0;
			if (analyzedText.getStringWithoutMarkup().trim().length() > 0) {
				for (WordRegion wr : key.getWords())
				{
					minWidth = Math.max(minWidth, drawer.textWidth(
							analyzedText.getStringWithoutMarkup().substring(wr.getBegin(), wr.getEnd())));
				}
			}
			// add the Buffer and small number, so the text can be drawn with the returned width (see splitStringAlgorithm)
			minWidth += endBuffer(drawer) + 0.01;
			setCachedMinWidth(key, minWidth);
			return minWidth;
		}
	}

	/**
	 * Returns the minimum width which is needed to draw the given lines
	 * @param textLines each element must be a single line (no \r \n)
	 * @param drawer
	 * @return the minimum width which is needed to draw the given lines
	 */
	public static double getTextMinWidth(String[] textLines, DrawHandler drawer) {
		double minWidth = 0;
		for (String line : textLines) {
			minWidth = Math.max(minWidth, getTextMinWidth(line, drawer));
		}
		return minWidth;
	}

	/**
	 *
	 * @param text
	 * @return all the words which are separated by whitespaces (first word contains all leading whitespaces)
	 */
	private static WordRegion[] splitIntoWords(String text) {
		WordRegion[] words = getCachedWords(text);
		if (words == null) {
			words = new WordRegion[0];
			if (text.trim().length() > 0) {
				int wordStart = 0;
				int current = 0;
				// add the leading white spaces to the first word to keep indentation
				while (isWhitespace(text.charAt(current))) {
					current++;
				}
				current++;
				boolean inWord = true;
				for (; current < text.length(); current++) {
					if (inWord) {
						if (isWhitespace(text.charAt(current))) {
							words = Arrays.copyOf(words, words.length + 1);
							words[words.length - 1] = new WordRegion(wordStart, current);
							inWord = false;
						}
					}
					else {
						if (!isWhitespace(text.charAt(current))) {
							wordStart = current;
							inWord = true;
						}
					}
				}
				// if the last word isn't followed by a whitespace it won't get added in the loop
				if (inWord) {
					words = Arrays.copyOf(words, words.length + 1);
					words[words.length - 1] = new WordRegion(wordStart, current);
				}

			}
			setCachedWords(text, words);
		}
		return words;
	}

	private static boolean isWhitespace(char c) {
		for (int i = 0; i < SPLIT_CHARS.length(); i++) {
			if (SPLIT_CHARS.charAt(i) == c) {
				return true;
			}
		}
		return false;
	}

	private static double endBuffer(DrawHandler drawer) {
		// used to subtract a buffer to make sure no character is hidden at the end (borrowed from TextSplitter)
		return drawer.textWidth("n");
	}

	// cache functions, so that the implementation (structure) of the cache can be changed
	// all functions will return null if no element was found

	private static WordRegion[] getCachedWords(String lineKey) {
		return wordCache.get(lineKey);
	}

	private static void setCachedWords(String lineKey, WordRegion[] words) {
		wordCache.put(lineKey, words);
	}

	private static Double getCachedMinWidth(MinWidthCacheKey key) {
		return minWidthCache.get(key);
	}

	private static Double getCachedMinWidth(WordRegion[] words, Style style, Set<FormatLabels> format) {
		return getCachedMinWidth(new MinWidthCacheKey(words, style, format));
	}

	private static void setCachedMinWidth(MinWidthCacheKey key, Double value) {
		minWidthCache.put(key, value);
	}

	private static WordwrapCacheValue getCachedWordwrap(WordwrapCacheKey key) {
		return wordwrapCache.get(key);
	}

	private static WordwrapCacheValue getCachedWordwrap(WordRegion[] words, double width, Style style, Set<FormatLabels> format) {
		return getCachedWordwrap(new WordwrapCacheKey(words, width, style, format));
	}

	private static void setCachedWordwrap(WordwrapCacheKey key, WordwrapCacheValue value) {
		wordwrapCache.put(key, value);
	}

	private static void setCachedWordwrap(WordwrapCacheKey key, StringStyle[] wrappedLines, double height) {
		wordwrapCache.put(key, new WordwrapCacheValue(wrappedLines, height));
	}

	private static void setCachedWordwrap(WordRegion[] words, double width, Style style, Set<FormatLabels> format, StringStyle[] wrappedLines, double height) {
		wordwrapCache.put(new WordwrapCacheKey(words, width, style, format), new WordwrapCacheValue(wrappedLines, height));
	}

	private static void setCachedWordwrap(WordwrapCacheKey key, String[] wrappedLines, double height) {
		wordwrapCache.put(key, new WordwrapCacheValue(wrappedLines, key.format, height));
	}

	private static void setCachedWordwrap(WordRegion[] words, double width, Style style, Set<FormatLabels> format, String[] wrappedLines, double height) {
		wordwrapCache.put(new WordwrapCacheKey(words, width, style, format), new WordwrapCacheValue(wrappedLines, format, height));
	}

	/**
	 * Contains the start and end of a word, can be directly used with substring
	 */
	private static class WordRegion {
		private final int begin;
		private final int end; // last character at end - 1. Thus the length is end-begin.

		public WordRegion(int begin, int end) {
			super();
			this.begin = begin;
			this.end = end;
		}

		public int getBegin() {
			return begin;
		}

		public int getEnd() {
			return end;
		}
	}

	private static class MinWidthCacheKey {
		private final WordRegion[] words;
		private final Style style; // must be part of key, because text width also depends on styling like fontsize
		private final Set<FormatLabels> format;

		public MinWidthCacheKey(WordRegion[] words, Style style, Set<FormatLabels> format) {
			super();
			this.words = words;
			this.style = style;
			this.format = format;
		}

		public WordRegion[] getWords() {
			return words;
		}

		public Style getStyle() {
			return style;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + (words == null ? 0 : words.hashCode());
			result = prime * result + (style == null ? 0 : style.hashCode());
			for (FormatLabels fl : format) {
				result = prime * result + fl.hashCode();
			}
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			MinWidthCacheKey other = (MinWidthCacheKey) obj;
			if (words == null) {
				if (other.words != null) {
					return false;
				}
			}
			else if (!words.equals(other.words)) {
				return false;
			}
			if (style == null) {
				if (other.style != null) {
					return false;
				}
			}
			else if (!style.equals(other.style)) {
				return false;
			}
			if (format == null) {
				if (other.format != null) {
					return false;
				}
			}
			else if (format.size() != other.format.size()) {
				return false;
			}
			else if (!format.containsAll(other.format)) {
				return false;
			}
			return true;
		}

	}

	private static class WordwrapCacheKey {
		private final WordRegion[] words;
		private final double width;
		private final Style style; // must be part of key, because text width also depends on styling like fontsize
		private final Set<FormatLabels> format;

		public WordwrapCacheKey(WordRegion[] words, double width, Style style, Set<FormatLabels> format) {
			super();
			this.words = words;
			this.width = width;
			this.style = style;
			this.format = format;
		}

		public WordRegion[] getWords() {
			return words;
		}

		public double getWidth() {
			return width;
		}

		public Style getStyle() {
			return style;
		}

		public Set<FormatLabels> getFormat() {
			return format;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + (words == null ? 0 : words.hashCode());
			result = prime * result + (style == null ? 0 : style.hashCode());
			long temp;
			temp = Double.doubleToLongBits(width);
			result = prime * result + (int) (temp ^ temp >>> 32);
			for (FormatLabels fl : format) {
				result = prime * result + fl.hashCode();
			}
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			WordwrapCacheKey other = (WordwrapCacheKey) obj;
			if (words == null) {
				if (other.words != null) {
					return false;
				}
			}
			else if (!words.equals(other.words)) {
				return false;
			}
			if (style == null) {
				if (other.style != null) {
					return false;
				}
			}
			else if (!style.equals(other.style)) {
				return false;
			}
			if (Double.doubleToLongBits(width) != Double.doubleToLongBits(other.width)) {
				return false;
			}
			if (format == null) {
				if (other.format != null) {
					return false;
				}
			}
			else if (format.size() != other.format.size()) {
				return false;
			}
			else if (!format.containsAll(other.format)) {
				return false;
			}
			return true;
		}

	}

	private static class WordwrapCacheValue {
		// Style is included although the format is already included in the key, because nearly every time the line are retrieved the style is needed
		private final StringStyle[] wrappedLines;
		private final double height;

		public WordwrapCacheValue(StringStyle[] wrappedLines, double height) {
			super();
			this.wrappedLines = wrappedLines;
			this.height = height;
		}

		public WordwrapCacheValue(String[] wrappedLines, Set<FormatLabels> format, double height) {
			super();
			this.wrappedLines = new StringStyle[wrappedLines.length];
			for (int i = 0; i < wrappedLines.length; i++) {
				this.wrappedLines[i] = new StringStyle(format, wrappedLines[i]);
			}
			this.height = height;
		}

		public StringStyle[] getWrappedLines() {
			return wrappedLines;
		}

		public double getHeight() {
			return height;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			long temp;
			temp = Double.doubleToLongBits(height);
			result = prime * result + (int) (temp ^ temp >>> 32);
			result = prime * result + Arrays.hashCode(wrappedLines);
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			WordwrapCacheValue other = (WordwrapCacheValue) obj;
			if (Double.doubleToLongBits(height) != Double.doubleToLongBits(other.height)) {
				return false;
			}
			if (!Arrays.equals(wrappedLines, other.wrappedLines)) {
				return false;
			}
			return true;
		}
	}
}