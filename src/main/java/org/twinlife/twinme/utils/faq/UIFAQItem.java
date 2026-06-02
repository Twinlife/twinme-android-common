/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils.faq;

public abstract class UIFAQItem {

    public  enum FAQItemType {
        CATEGORY,
        ARTICLE
    }

    public abstract FAQItemType getType();

    public abstract String getTitle();

    public abstract int getArticleId();
}
