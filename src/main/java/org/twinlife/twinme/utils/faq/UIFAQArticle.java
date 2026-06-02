/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils.faq;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinme.services.AbstractTwinmeService;

import java.util.List;

public class UIFAQArticle extends UIFAQItem {

    private final int mArticleId;

    @NonNull
    private final String mQuestion;
    @NonNull
    private final String mAnswer;
    @Nullable
    private final String mImage;
    @Nullable
    private final String mVideo;
    @Nullable
    private final List<String> mTags;

    public UIFAQArticle(int articleId, @NonNull String question, @NonNull String answer, @Nullable String image, @Nullable String video, @Nullable List<String> tags) {

        mArticleId = articleId;
        mQuestion = question;
        mAnswer = answer;
        mImage = image;
        mVideo = video;
        mTags = tags;
    }

    @Override
    public FAQItemType getType() {

        return FAQItemType.ARTICLE;
    }

    @Override
    public String getTitle() {

        return mQuestion;
    }

    @Override
    public int getArticleId() {

        return mArticleId;
    }

    public String getAnswer() {

        return mAnswer;
    }

    public String getImage() {

        return mImage;
    }

    public String getVideo() {

        return mVideo;
    }

    public List<String> getTags() {

        return mTags;
    }

    public boolean containsSearchText(String text) {

        if (text == null) {
            return false;
        }

        String searchText = text.trim();
        if (searchText.isEmpty()) {
            return false;
        }

        if (searchInString(mQuestion, searchText)) {
            return true;
        }

        if (searchInString(mAnswer, searchText)) {
            return true;
        }

        if (mTags != null) {
            for (String tag : mTags) {
                if (searchInString(tag, searchText)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean searchInString(@Nullable String string, @NonNull String text) {

        if (string == null) {
            return false;
        }

        String normalizedString = AbstractTwinmeService.normalize(string).toLowerCase();
        String normalizedText = AbstractTwinmeService.normalize(text).toLowerCase();

        return normalizedString.contains(normalizedText);
    }
}