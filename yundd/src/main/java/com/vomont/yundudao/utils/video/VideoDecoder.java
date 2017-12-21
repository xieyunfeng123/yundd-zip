package com.vomont.yundudao.utils.video;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.vomont.yundudao.upload.VideoManager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaMuxer.OutputFormat;
import android.util.Log;

@SuppressLint("SimpleDateFormat") public class VideoDecoder
{
    private final static String TAG = "VideoDecoder";
    
    private MediaExtractor mediaExtractor;
    
    private MediaFormat mediaFormat;
    
    private MediaMuxer mediaMuxer;
    
    private String mime = null;
    
    private String name;
    
    public VideoDecoder(Context context)
    {
    }
    
    @SuppressLint("NewApi")
    public String decodeVideo(String url, long clipPoint, long clipDuration)
    {
        int videoTrackIndex = -1;
        int audioTrackIndex = -1;
        int videoMaxInputSize = 0;
        int audioMaxInputSize = 0;
        int sourceVTrack = 0;
        int sourceATrack = 0;
        long videoDuration, audioDuration;
        // 创建分离器
        mediaExtractor = new MediaExtractor();
        try
        {
            // 设置文件路径
            mediaExtractor.setDataSource(url);
            // 创建合成器
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            name = simpleDateFormat.format(new Date());
            
            mediaMuxer = new MediaMuxer(VideoManager.path + "/" + name + ".mp4", OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
        catch (Exception e)
        {
            Log.e(TAG, "error path" + e.getMessage());
        }
        // 获取每个轨道的信息
        for (int i = 0; i < mediaExtractor.getTrackCount(); i++)
        {
            try
            {
                mediaFormat = mediaExtractor.getTrackFormat(i);
                mime = mediaFormat.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/"))
                {
                    sourceVTrack = i;
                    int width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
                    int height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    videoMaxInputSize = mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    videoDuration = mediaFormat.getLong(MediaFormat.KEY_DURATION);
                    // 检测剪辑点和剪辑时长是否正确
                    if (clipPoint >= videoDuration)
                    {
                        Log.e(TAG, "clip point is error!");
                        return null;
                    }
                    if ((clipDuration != 0) && ((clipDuration + clipPoint) >= videoDuration))
                    {
                        Log.e(TAG, "clip duration is error!");
                        return null;
                    }
                    Log.d(TAG, "width and height is " + width + " " + height + ";maxInputSize is " + videoMaxInputSize + ";duration is " + videoDuration);
                    // 向合成器添加视频轨
                    videoTrackIndex = mediaMuxer.addTrack(mediaFormat);
                }
                else if (mime.startsWith("audio/"))
                {
                    sourceATrack = i;
                    int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    audioMaxInputSize = mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    audioDuration = mediaFormat.getLong(MediaFormat.KEY_DURATION);
                    Log.d(TAG, "sampleRate is " + sampleRate + ";channelCount is " + channelCount + ";audioMaxInputSize is " + audioMaxInputSize + ";audioDuration is " + audioDuration);
                    // 添加音轨
                    audioTrackIndex = mediaMuxer.addTrack(mediaFormat);
                }
                Log.d(TAG, "file mime is " + mime);
            }
            catch (Exception e)
            {
                Log.e(TAG, " read error " + e.getMessage());
                return null;
            }
        }
        // 分配缓冲
        ByteBuffer inputBuffer = ByteBuffer.allocate(videoMaxInputSize);
        // 根据官方文档的解释MediaMuxer的start一定要在addTrack之后
        mediaMuxer.start();
        // 视频处理部分
        mediaExtractor.selectTrack(sourceVTrack);
        MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
        videoInfo.presentationTimeUs = 0;
        long videoSampleTime;
        // 获取源视频相邻帧之间的时间间隔。(1)
        {
            mediaExtractor.readSampleData(inputBuffer, 0);
            // skip first I frame
            if (mediaExtractor.getSampleFlags() == MediaExtractor.SAMPLE_FLAG_SYNC)
                mediaExtractor.advance();
            mediaExtractor.readSampleData(inputBuffer, 0);
            long firstVideoPTS = mediaExtractor.getSampleTime();
            mediaExtractor.advance();
            mediaExtractor.readSampleData(inputBuffer, 0);
            long SecondVideoPTS = mediaExtractor.getSampleTime();
            videoSampleTime = Math.abs(SecondVideoPTS - firstVideoPTS);
            Log.d(TAG, "videoSampleTime is " + videoSampleTime);
        }
        // 选择起点
        mediaExtractor.seekTo(clipPoint, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        while (true)
        {
            int sampleSize = mediaExtractor.readSampleData(inputBuffer, 0);
            if (sampleSize < 0)
            {
                // 这里一定要释放选择的轨道，不然另一个轨道就无法选中了
                mediaExtractor.unselectTrack(sourceVTrack);
                break;
            }
            int trackIndex = mediaExtractor.getSampleTrackIndex();
            // 获取时间戳
            long presentationTimeUs = mediaExtractor.getSampleTime();
            // 获取帧类型，只能识别是否为I帧
            int sampleFlag = mediaExtractor.getSampleFlags();
            Log.d(TAG, "trackIndex is " + trackIndex + ";presentationTimeUs is " + presentationTimeUs + ";sampleFlag is " + sampleFlag + ";sampleSize is " + sampleSize);
            // 剪辑时间到了就跳出
            if ((clipDuration != 0) && (presentationTimeUs > (clipPoint + clipDuration)))
            {
                mediaExtractor.unselectTrack(sourceVTrack);
                break;
            }
            mediaExtractor.advance();
            videoInfo.offset = 0;
            videoInfo.size = sampleSize;
            videoInfo.flags = sampleFlag;
            mediaMuxer.writeSampleData(videoTrackIndex, inputBuffer, videoInfo);
            videoInfo.presentationTimeUs += videoSampleTime;// presentationTimeUs;
        }
        // 音频部分
        mediaExtractor.selectTrack(sourceATrack);
        MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        audioInfo.presentationTimeUs = 0;
        long audioSampleTime;
        // 获取音频帧时长
        {
            mediaExtractor.readSampleData(inputBuffer, 0);
            // skip first sample
            if (mediaExtractor.getSampleTime() == 0)
                mediaExtractor.advance();
            mediaExtractor.readSampleData(inputBuffer, 0);
            long firstAudioPTS = mediaExtractor.getSampleTime();
            mediaExtractor.advance();
            mediaExtractor.readSampleData(inputBuffer, 0);
            long SecondAudioPTS = mediaExtractor.getSampleTime();
            audioSampleTime = Math.abs(SecondAudioPTS - firstAudioPTS);
            Log.d(TAG, "AudioSampleTime is " + audioSampleTime);
        }
        mediaExtractor.seekTo(clipPoint, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true)
        {
            int sampleSize = mediaExtractor.readSampleData(inputBuffer, 0);
            if (sampleSize < 0)
            {
                mediaExtractor.unselectTrack(sourceATrack);
                break;
            }
            int trackIndex = mediaExtractor.getSampleTrackIndex();
            long presentationTimeUs = mediaExtractor.getSampleTime();
            Log.d(TAG, "trackIndex is " + trackIndex + ";presentationTimeUs is " + presentationTimeUs);
            if ((clipDuration != 0) && (presentationTimeUs > (clipPoint + clipDuration)))
            {
                mediaExtractor.unselectTrack(sourceATrack);
                break;
            }
            mediaExtractor.advance();
            audioInfo.offset = 0;
            audioInfo.size = sampleSize;
            try
            {
                mediaMuxer.writeSampleData(audioTrackIndex, inputBuffer, audioInfo);
            }
            catch (Exception e)
            {
                mediaMuxer.stop();
                mediaMuxer.release();
                mediaExtractor.release();
                mediaExtractor = null;
                Log.e("insert", e.toString());
                return null;
            }
            
            audioInfo.presentationTimeUs += audioSampleTime;// presentationTimeUs;
        }
        // 全部写完后释放MediaMuxer和MediaExtractor
        mediaMuxer.stop();
        mediaMuxer.release();
        mediaExtractor.release();
        mediaExtractor = null;
        return name;
    }
}
