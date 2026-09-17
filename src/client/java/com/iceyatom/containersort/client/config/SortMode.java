package com.iceyatom.containersort.client.config;

/**
 * Controls the ordering applied after stacks are consolidated (FR-07/FR-08).
 */
public enum SortMode {
	/** Order by the item's displayed name in the client's active locale, registry-ID tiebreak. */
	ALPHABETICAL,
	/** Order by registry ID ({@code namespace:path}) — fully locale-independent. */
	ITEM_ID,
	/** Order by stack count, descending; name/ID tiebreak. */
	COUNT_DESC
}
