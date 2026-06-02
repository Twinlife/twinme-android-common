/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils.faq;

import androidx.annotation.NonNull;

import java.util.List;

public class UIFAQCategory extends UIFAQItem {

    @NonNull
    private final String mTitle;
    @NonNull
    private final List<UIFAQArticle> mArticles;

    public UIFAQCategory(@NonNull String title, @NonNull List<UIFAQArticle> articles) {

        mTitle = title;
        mArticles = articles;
    }

    @Override
    public int getArticleId() {

        return -1;
    }

    @Override
    public FAQItemType getType() {

        return FAQItemType.CATEGORY;
    }

    @Override
    public String getTitle() {

        return mTitle;
    }

    public List<UIFAQArticle> getArticles() {

        return mArticles;
    }

    public void addArticle(UIFAQArticle article) {

        mArticles.add(article);
    }
}